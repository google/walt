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

#import "NSArray+Extensions.h"

@implementation NSArray (WALTExtensions)
- (NSNumber *)medianValue {
  NSArray<NSNumber *> *sorted = [self sortedArrayUsingSelector:@selector(compare:)];
  const NSUInteger count = sorted.count;
  if (count == 0) {
    return nil;
  }
  
  if (count % 2) {
    return [sorted objectAtIndex:count / 2];
  } else {
    return [NSNumber numberWithDouble:0.5 * ([sorted objectAtIndex:count / 2].doubleValue +
                                             [sorted objectAtIndex:count / 2 - 1].doubleValue)];
  }
}
@end
