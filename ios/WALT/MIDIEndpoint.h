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

#include <CoreMIDI/CoreMIDI.h>
#import <Foundation/Foundation.h>

/** An abstract MIDI endpoint (input source or output destination). */
@interface MIDIEndpoint : NSObject
@property (readonly, nonatomic) MIDIEndpointRef endpoint;
@property (readonly, nonatomic) NSString *name;
@property (readonly, nonatomic, getter=isOnline) BOOL online;
@end

@interface MIDIDestination : MIDIEndpoint
/** Returns an NSArray of all MIDI output destinations currently available on the system. */
+ (NSArray<MIDIDestination *> *)allDestinations;
@end

@interface MIDISource : MIDIEndpoint
/** Returns an NSArray of all MIDI input sources currently available on the system. */
+ (NSArray<MIDISource *> *)allSources;
@end
