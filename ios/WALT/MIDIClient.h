/*
 * Copyright (C) 2016 The Android Open Source Project
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

#import <Foundation/Foundation.h>

@class MIDIClient;
@class MIDIDestination;
@class MIDISource;

extern NSString * const MIDIClientErrorDomain;

/**
 * Callbacks for MIDIClient changes.
 *
 * Note that these methods may not be called on the main thread.
 */
@protocol MIDIClientDelegate <NSObject>
/** Called when a MIDIClient receives data from a connected source. */
- (void)MIDIClient:(MIDIClient *)client receivedData:(NSData *)message;

@optional
/** Called when a MIDI I/O error occurs on the client's endpoints. */
- (void)MIDIClient:(MIDIClient *)client receivedError:(NSError *)error;

/** Called when a MIDI endpoint has been added to the system. */
- (void)MIDIClientEndpointAdded:(MIDIClient *)client;

/** Called when a MIDI endpoint has been removed to the system. */
- (void)MIDIClientEndpointRemoved:(MIDIClient *)client;

/** Called when the configuration of a MIDI object attached to the system has changed. */
- (void)MIDIClientConfigurationChanged:(MIDIClient *)client;
@end

/** A MIDI client that can read data from a MIDI source and write data to a MIDI destination. */
@interface MIDIClient : NSObject
/** The source attached by -connectToSource:error:. */
@property (readonly, nonatomic) MIDISource *source;

/** The destination attached by -connectToDestination:error:. */
@property (readonly, nonatomic) MIDIDestination *destination;

@property (nonatomic, weak) id<MIDIClientDelegate> delegate;

/**
 * Creates a new MIDI client with a friendly name.
 *
 * If an error occurs, nil is returned and the error is populated with a description of the issue.
 */
- (instancetype)initWithName:(NSString *)name error:(NSError **)error;

/** Attaches an input source to the client. */
- (BOOL)connectToSource:(MIDISource *)source error:(NSError **)error;

/** Attaches an output destination to the client. */
- (BOOL)connectToDestination:(MIDIDestination *)destination error:(NSError **)error;

/** Sends a MIDI packet of data to the client's output destination. */
- (BOOL)sendData:(NSData *)data error:(NSError **)error;
@end
