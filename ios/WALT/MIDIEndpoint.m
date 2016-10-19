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

#import "MIDIEndpoint.h"

@interface MIDIEndpoint ()
@property (readwrite, nonatomic, assign) MIDIEndpointRef endpoint;
@end

@implementation MIDIEndpoint
- (NSString *)name {
  CFStringRef result = CFSTR("");
  MIDIObjectGetStringProperty(self.endpoint, kMIDIPropertyDisplayName, &result);
  return CFBridgingRelease(result);
}

- (BOOL)isOnline {
  SInt32 result = 1;
  MIDIObjectGetIntegerProperty(self.endpoint, kMIDIPropertyOffline, &result);
  return (result == 0 ? YES : NO);
}
@end

@implementation MIDIDestination
+ (NSArray *)allDestinations {
  NSMutableArray<MIDIDestination *> *destinations =
      [[NSMutableArray<MIDIDestination *> alloc] init];
  
  ItemCount destinationCount = MIDIGetNumberOfDestinations();
  for (ItemCount i = 0; i < destinationCount; ++i) {
    MIDIEndpointRef endpoint = MIDIGetDestination(i);
    if (endpoint) {
      MIDIDestination *destination = [[MIDIDestination alloc] init];
      destination.endpoint = endpoint;
      [destinations addObject:destination];
    } else {
      NSLog(@"Error getting destination at index %lud, skipping.", i);
    }
  }
  
  return destinations;
}
@end

@implementation MIDISource
+ (NSArray *)allSources {
  NSMutableArray<MIDISource *> *sources = [[NSMutableArray<MIDISource *> alloc] init];
  
  ItemCount sourceCount = MIDIGetNumberOfSources();
  for (ItemCount i = 0; i < sourceCount; ++i) {
    MIDIEndpointRef endpoint = MIDIGetSource(i);
    if (endpoint) {
      MIDISource *source = [[MIDISource alloc] init];
      source.endpoint = endpoint;
      [sources addObject:source];
    } else {
      NSLog(@"Error getting source at index %lud, skipping.", i);
    }
  }
  
  return sources;
}
@end
