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

#import "WALTAppDelegate.h"

#include <sys/utsname.h>

#import "UIAlertView+Extensions.h"
#import "WALTClient.h"
#import "WALTLogger.h"

@interface WALTAppDelegate ()
@property (readwrite, nonatomic) WALTClient *client;
@end

@implementation WALTAppDelegate
- (BOOL)application:(UIApplication *)application
    willFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
  struct utsname systemInfo;
  if (uname(&systemInfo) != 0) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"System Error"
                                                    message:@"Cannot identify system."
                                                   delegate:nil
                                          cancelButtonTitle:@"Dismiss"
                                          otherButtonTitles:nil];
    [alert show];
  } else {
    [[WALTLogger sessionLogger] appendFormat:@"DEVICE\t%s\t%s\t%s\t%s\t%s\n",
        systemInfo.machine,
        systemInfo.sysname,
        systemInfo.release,
        systemInfo.nodename,
        systemInfo.version];
  }

  NSError *error = nil;
  self.client = [[WALTClient alloc] initWithError:&error];
  if (!self.client) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  }

  return YES;
}
@end
