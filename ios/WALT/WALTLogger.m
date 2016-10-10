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

#import "WALTLogger.h"

@implementation WALTLogger {
  NSMutableString *_buffer;
}

+ (instancetype)sessionLogger {
  static WALTLogger *sessionLogger = nil;
  static dispatch_once_t token;
  dispatch_once(&token, ^{
    sessionLogger = [[self alloc] init];
  });
  return sessionLogger;
}

- (instancetype)init {
  if ((self = [super init])) {
    _buffer = [[NSMutableString alloc] init];
  }
  return self;
}

- (void)appendString:(NSString *)string {
  @synchronized (_buffer) {
    [_buffer appendString:string];
  }
}

- (void)appendFormat:(NSString *)format, ... {
  va_list args;
  va_start(args, format);
  NSString *formatted = [[NSString alloc] initWithFormat:format arguments:args];
  [_buffer appendString:formatted];
  va_end(args);
}

- (void)clear {
  @synchronized (_buffer) {
    [_buffer setString:[NSString string]];
  }
}

- (BOOL)writeToURL:(NSURL *)url error:(NSError **)error {
  @synchronized (_buffer) {
    return [_buffer writeToURL:url
                    atomically:YES
                      encoding:NSUTF8StringEncoding
                         error:error];
  }
}

- (NSString *)stringValue {
  @synchronized (_buffer) {
    return [NSString stringWithString:_buffer];
  }
}
@end
