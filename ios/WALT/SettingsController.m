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

#import "SettingsController.h"

#import "UIAlertView+Extensions.h"
#import "WALTAppDelegate.h"
#import "WALTClient.h"

@implementation SettingsController {
  WALTClient *_client;
  NSString *_status;
}

- (void)viewDidLoad {
  [super viewDidLoad];
  
  _client = ((WALTAppDelegate *)[UIApplication sharedApplication].delegate).client;
}

- (void)viewWillAppear:(BOOL)animated {
  [super viewWillAppear:animated];
  
  _status = [NSString string];
  [self.tableView reloadData];
}

- (IBAction)ping:(id)sender {
  NSTimeInterval start = _client.currentTime;
  NSError *error = nil;
  if (![_client sendCommand:WALTPingCommand error:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  } else {
    NSData *response = [_client readResponseWithTimeout:kWALTReadTimeout];
    if (!response) {
      _status = @"Timed out waiting for ping response.";
    } else {
      NSTimeInterval delta = _client.currentTime - start;
      _status = [NSString stringWithFormat:@"Ping response in %.2f ms.", delta * 1000];
    }
  }
  [self.tableView reloadData];
}

- (IBAction)checkDrift:(id)sender {
  NSError *error = nil;
  if (![_client updateSyncBoundsWithError:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  } else {
    _status = [NSString stringWithFormat:@"Remote clock delayed between %lld and %lld µs.",
                  _client.minError, _client.maxError];
  }
  [self.tableView reloadData];
}

- (NSIndexPath *)tableView:(UITableView *)tableView
  willSelectRowAtIndexPath:(NSIndexPath *)indexPath {
  if (indexPath.section == 0 && indexPath.row == 0) {
    // "Ping"
    [self ping:tableView];
    return nil;
  } else if (indexPath.section == 0 && indexPath.row == 1) {
    // "Check Drift"
    [self checkDrift:tableView];
    return nil;
  }
  return indexPath;
}

- (NSString *)tableView:(UITableView *)tableView titleForFooterInSection:(NSInteger)section {
  return (section == 0 ? _status : nil);
}
@end
