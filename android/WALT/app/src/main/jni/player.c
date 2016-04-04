/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android/log.h>
#include <assert.h>
#include <jni.h>
#include <malloc.h>
#include <math.h>
#include <sys/types.h>

// for native audio
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>

#include "sync_clock.h"

// logging
#define APPNAME "WALT"

// engine interfaces
static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine = NULL;

// output mix interfaces
static SLObjectItf outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bqPlayerPlay = NULL;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue = NULL;

// recorder interfaces
static SLObjectItf recorderObject = NULL;
static SLRecordItf recorderRecord;
static SLAndroidSimpleBufferQueueItf recorderBufferQueue;
static volatile int bqPlayerRecorderBusy = 0;

static unsigned int recorder_frames;
static short* recorderBuffer;
static unsigned recorderSize = 0;

static unsigned int framesPerBuffer;

#define CHANNELS 1  // 1 for mono, 2 for stereo

// Each short represents a 16-bit audio sample
static short* beepBuffer = NULL;
static short* silenceBuffer = NULL;
static unsigned int bufferSizeInBytes = 0;

#define MAXIMUM_AMPLITUDE_VALUE 32767

// how many times to play the wave table (so we can actually hear it)
#define BUFFERS_TO_PLAY 10

static unsigned buffersRemaining = 0;
static short isPlaying = 0;

// TODO: figure out a better way to access clk?
extern struct clock_connection clk;

// Timestamps
// te - enqueue time
// tc - callback time
int64_t te_play = 0, te_rec = 0, tc_rec = 0;

/**
 * Create wave tables for audio out.
 */
void createWaveTables(){
    bufferSizeInBytes = framesPerBuffer * sizeof(*beepBuffer);
    silenceBuffer = malloc(bufferSizeInBytes);
    beepBuffer = malloc(bufferSizeInBytes);


    __android_log_print(ANDROID_LOG_VERBOSE,
                        APPNAME,
                        "Creating wave tables, 1 channel. Frames: %i Buffer size (bytes): %i",
                        framesPerBuffer,
                        bufferSizeInBytes);

    unsigned int i;
    for (i = 0; i < framesPerBuffer; i++) {
        silenceBuffer[i] = 0;
        beepBuffer[i] = (i & 2 - 1) * MAXIMUM_AMPLITUDE_VALUE;
        // This fills a buffer that looks like [min, min, max, max, min, min...]
        // which is a square wave at 1/4 frequency of the sampling rate
        // for 48kHz sampling this is 12kHz pitch, still well audible.
    }
}

// this callback handler is called every time a buffer finishes playing
void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
    if (bq == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "buffer queue is null");
    }
    assert(bq == bqPlayerBufferQueue);
    assert(NULL == context);

    // If not playing, enqueue silence to keep the player in warmed up state
    short* bufferPtr = silenceBuffer;

    if (isPlaying > 0 && buffersRemaining > 0) {
        if(buffersRemaining == BUFFERS_TO_PLAY) {
            // Enqueue the first non-silent buffer, save the timestamp
            te_play = micros(&clk);
        }
        bufferPtr = beepBuffer;
        buffersRemaining--;
    }

    SLresult result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, bufferPtr, bufferSizeInBytes);
    assert(SL_RESULT_SUCCESS == result);
}

jlong Java_org_chromium_latency_walt_AudioFragment_playTone(JNIEnv* env, jclass clazz){

    int64_t t_start = micros(&clk);
    te_play = 0;

    __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Playing tone");
    buffersRemaining = BUFFERS_TO_PLAY;
    isPlaying = 1;

    return (jlong) t_start;
}


// create the engine and output mix objects
void Java_org_chromium_latency_walt_AudioFragment_createEngine(JNIEnv* env, jclass clazz)
{
    __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Creating audio engine");

    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // create output mix,
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
}


// create buffer queue audio player
void Java_org_chromium_latency_walt_AudioFragment_createBufferQueueAudioPlayer(JNIEnv* env,
        jclass clazz, jint optimalFrameRate, jint optimalFramesPerBuffer)
{
    __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Creating audio player with frame rate %d and frames per buffer %d",
                        optimalFrameRate, optimalFramesPerBuffer);

    framesPerBuffer = optimalFramesPerBuffer;
    createWaveTables();

    SLresult result;

    // configure the audio source (supply data through a buffer queue in PCM format)
    SLDataLocator_AndroidSimpleBufferQueue locator_bufferqueue_source;
    SLDataFormat_PCM format_pcm;
    SLDataSource audio_source;

    // source location
    locator_bufferqueue_source.locatorType = SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE;
    locator_bufferqueue_source.numBuffers = 1;

    // source format
    format_pcm.formatType = SL_DATAFORMAT_PCM;
    format_pcm.numChannels = 1;

    // Note: this shouldn't be called samplesPerSec it should be called *framesPerSec*
    // because when channels = 2 then there are 2 samples per frame.
    format_pcm.samplesPerSec = (SLuint32) optimalFrameRate * 1000;
    format_pcm.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
    format_pcm.containerSize = 16;
    format_pcm.channelMask = SL_SPEAKER_FRONT_CENTER;
    format_pcm.endianness = SL_BYTEORDER_LITTLEENDIAN;

    audio_source.pLocator = &locator_bufferqueue_source;
    audio_source.pFormat = &format_pcm;

    // configure the output: An output mix sink
    SLDataLocator_OutputMix locator_output_mix;
    SLDataSink audio_sink;

    locator_output_mix.locatorType = SL_DATALOCATOR_OUTPUTMIX;
    locator_output_mix.outputMix = outputMixObject;

    audio_sink.pLocator = &locator_output_mix;
    audio_sink.pFormat = NULL;

    // create audio player
    // Note: Adding other output interfaces here will result in your audio being routed using the
    // normal path NOT the fast path
    const SLInterfaceID interface_ids[2] = { SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_VOLUME };
    const SLboolean interfaces_required[2] = { SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE };

    result = (*engineEngine)->CreateAudioPlayer(
        engineEngine,
        &bqPlayerObject,
        &audio_source,
        &audio_sink,
        2, // Number of interfaces
        interface_ids,
        interfaces_required
    );

    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
            &bqPlayerBufferQueue);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // set the player's state to playing
    result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay, SL_PLAYSTATE_PLAYING);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // enqueue some silence
    result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, silenceBuffer, bufferSizeInBytes);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

}

// this callback handler is called every time a buffer finishes recording
void bqRecorderCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
    tc_rec = micros(&clk);
    assert(bq == recorderBufferQueue);
    assert(NULL == context);

    // for streaming recording, here we would call Enqueue to give recorder the next buffer to fill
    // but instead, this is a one-time buffer so we stop recording
    SLresult result;
    result = (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_STOPPED);
    if (SL_RESULT_SUCCESS == result) {
        recorderSize = recorder_frames * sizeof(short);
    }
    bqPlayerRecorderBusy = 0;

    //// TODO: Use small buffers and re-enqueue each time
    // result = (*recorderBufferQueue)->Enqueue(recorderBufferQueue, recorderBuffer,
    //         recorder_frames * sizeof(short));
    // assert(SL_RESULT_SUCCESS == result);
}

// create audio recorder
jboolean Java_org_chromium_latency_walt_AudioFragment_createAudioRecorder(JNIEnv* env,
    jclass clazz, jint optimalFrameRate, jint framesToRecord)
{
    SLresult result;

    __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Creating audio recorder with frame rate %d and frames to record %d",
                        optimalFrameRate, framesToRecord);
    // Allocate buffer
    recorder_frames = framesToRecord;
    recorderBuffer = malloc(sizeof(*recorderBuffer) * recorder_frames);

    // configure audio source
    SLDataLocator_IODevice loc_dev = {
            SL_DATALOCATOR_IODEVICE,
            SL_IODEVICE_AUDIOINPUT,
            SL_DEFAULTDEVICEID_AUDIOINPUT,
            NULL
        };
    SLDataSource audioSrc = {&loc_dev, NULL};

    // configure audio sink
    SLDataLocator_AndroidSimpleBufferQueue loc_bq;
    loc_bq.locatorType = SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE;
    loc_bq.numBuffers = 2;


    // source format
    SLDataFormat_PCM format_pcm;
    format_pcm.formatType = SL_DATAFORMAT_PCM;
    format_pcm.numChannels = CHANNELS;
    // Note: this shouldn't be called samplesPerSec it should be called *framesPerSec*
    // because when channels = 2 then there are 2 samples per frame.
    format_pcm.samplesPerSec = (SLuint32) optimalFrameRate * 1000;
    format_pcm.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
    format_pcm.containerSize = 16;
    format_pcm.channelMask = SL_SPEAKER_FRONT_CENTER;
    format_pcm.endianness = SL_BYTEORDER_LITTLEENDIAN;


    SLDataSink audioSnk = {&loc_bq, &format_pcm};

    // create audio recorder
    // (requires the RECORD_AUDIO permission)
    const SLInterfaceID id[2] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
                                 SL_IID_ANDROIDCONFIGURATION };
    const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*engineEngine)->CreateAudioRecorder(engineEngine,
                                              &recorderObject,
                                              &audioSrc,
                                              &audioSnk,
                                              sizeof(id)/sizeof(id[0]),
                                              id, req);

    // Configure the voice recognition preset which has no
    // signal processing for lower latency.
    SLAndroidConfigurationItf inputConfig;
    result = (*recorderObject)->GetInterface(recorderObject,
                                            SL_IID_ANDROIDCONFIGURATION,
                                            &inputConfig);
    if (SL_RESULT_SUCCESS == result) {
        SLuint32 presetValue = SL_ANDROID_RECORDING_PRESET_VOICE_RECOGNITION;
        (*inputConfig)->SetConfiguration(inputConfig,
                                         SL_ANDROID_KEY_RECORDING_PRESET,
                                         &presetValue,
                                         sizeof(SLuint32));
    }

    // realize the audio recorder
    result = (*recorderObject)->Realize(recorderObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result) {
        return JNI_FALSE;
    }

    // get the record interface
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_RECORD, &recorderRecord);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the buffer queue interface
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
            &recorderBufferQueue);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // register callback on the buffer queue
    result = (*recorderBufferQueue)->RegisterCallback(recorderBufferQueue, bqRecorderCallback,
            NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Audio recorder created, buffer size: %d frames",
                        recorder_frames);

    return JNI_TRUE;
}


// set the recording state for the audio recorder
void Java_org_chromium_latency_walt_AudioFragment_startRecording(JNIEnv* env, jclass clazz)
{
    SLresult result;

    if( bqPlayerRecorderBusy) {
        return;
    }
    // in case already recording, stop recording and clear buffer queue
    result = (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_STOPPED);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
    result = (*recorderBufferQueue)->Clear(recorderBufferQueue);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // the buffer is not valid for playback yet
    recorderSize = 0;

    // enqueue an empty buffer to be filled by the recorder
    // (for streaming recording, we would enqueue at least 2 empty buffers to start things off)
    te_rec = micros(&clk);
    tc_rec = 0;
    result = (*recorderBufferQueue)->Enqueue(recorderBufferQueue, recorderBuffer,
            recorder_frames * sizeof(short));
    // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
    // which for this code example would indicate a programming error
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // start recording
    result = (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_RECORDING);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
    bqPlayerRecorderBusy = 1;
}

jshortArray Java_org_chromium_latency_walt_AudioFragment_getRecordedWave(JNIEnv *env, jclass cls)
{
    jshortArray result;
    result = (*env)->NewShortArray(env, recorder_frames);
    if (result == NULL) {
        return NULL; /* out of memory error thrown */
    }
    (*env)->SetShortArrayRegion(env, result, 0, recorder_frames, recorderBuffer);
    return result;
}

jlong Java_org_chromium_latency_walt_AudioFragment_getTcRec(JNIEnv *env, jclass cls) {
    return (jlong) tc_rec;
}

jlong Java_org_chromium_latency_walt_AudioFragment_getTeRec(JNIEnv *env, jclass cls) {
    return (jlong) te_rec;
}

jlong Java_org_chromium_latency_walt_AudioFragment_getTePlay(JNIEnv *env, jclass cls) {
    return (jlong) te_play;
}
