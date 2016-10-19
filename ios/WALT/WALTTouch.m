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

#import "WALTTouch.h"

@implementation WALTTouch
- (instancetype)initWithEvent:(UIEvent *)event {
  if ((self = [super init])) {
    if ([event allTouches].count > 1) {
      NSLog(@"Multiple touches in event; taking any.");
    }
    self.kernelTime = event.timestamp;
    
    UITouch *touch = [[event allTouches] anyObject];
    self.phase = touch.phase;
    if ([touch respondsToSelector:@selector(preciseLocationInView:)]) {  // iOS 9.1+
      self.location = [touch preciseLocationInView:nil];
    } else {
      self.location = [touch locationInView:nil];
    }
  }
  return self;
}
@end
