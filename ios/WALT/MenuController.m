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

#import "MenuController.h"

#import "UIAlertView+Extensions.h"
#import "WALTLogger.h"
#import "WALTAppDelegate.h"
#import "WALTClient.h"

@implementation MenuController {
  WALTClient *_client;
  UIActivityIndicatorView *_spinner;
}

- (void)viewDidLoad {
  [super viewDidLoad];
  
  _spinner =
      [[UIActivityIndicatorView alloc]
        initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleGray];
  
  _client = ((WALTAppDelegate *)[UIApplication sharedApplication].delegate).client;
}

- (void)viewDidAppear:(BOOL)animated {
  [super viewDidAppear:animated];

  [_client addObserver:self
            forKeyPath:@"connected"
               options:NSKeyValueObservingOptionInitial
               context:NULL];
}

- (void)dealloc {
  [_client removeObserver:self forKeyPath:@"connected"];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary *)change
                       context:(void *)context {
  if (_client.isConnected) {
    [_spinner stopAnimating];
    self.syncCell.accessoryView = nil;  // Display a checkmark.
    [[WALTLogger sessionLogger] appendString:@"WALT\tCONNECTED\n"];
    [[WALTLogger sessionLogger] appendFormat:@"SYNC\t%lld\t%lld\n",
        _client.minError, _client.maxError];
  } else {
    self.syncCell.accessoryView = _spinner;
    [_spinner startAnimating];    
    [[WALTLogger sessionLogger] appendString:@"WALT\tDISCONNECTED\n"];
    
    // Return to this view controller.
    UINavigationController *navigationController = self.navigationController;
    if (navigationController.visibleViewController != self) {
      [navigationController popToRootViewControllerAnimated:YES];
      
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error"
                                                      message:@"WALT disconnected."
                                                     delegate:nil
                                            cancelButtonTitle:@"Dismiss"
                                            otherButtonTitles:nil];
      [alert show];
    }
  }
  
  [self.tableView reloadData];  // Update accessory types.
}

- (void)shareLog:(id)sender {
  NSFileManager *fileManager = [NSFileManager defaultManager];
  NSArray *urls = [fileManager URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask];
  
  if (urls.count > 0) {
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    formatter.dateFormat = @"yyyy-MM-dd'T'HH-mm-ss";
    
    // Save the log to a file (which also allows it to be retrieved in iTunes/Xcode).
    NSString *logName = [NSString stringWithFormat:@"walt_%@.log",
                            [formatter stringFromDate:[NSDate date]]];
    NSURL *logURL = [urls.firstObject URLByAppendingPathComponent:logName];
    
    WALTLogger *logger = [WALTLogger sessionLogger];
    NSError *error = nil;
    if ([logger writeToURL:logURL error:&error]) {
      // Open a share sheet for the URL.
      UIActivityViewController *activityController =
          [[UIActivityViewController alloc] initWithActivityItems:@[logURL]
                                            applicationActivities:nil];
      [self presentViewController:activityController animated:YES completion:NULL];
    } else {
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"Log Write Error"
                                                        error:error];
      [alert show];
    }
  } else {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"Log Write Error"
                                                    message:@"Could not locate document directory."
                                                   delegate:nil
                                          cancelButtonTitle:@"Dismiss"
                                          otherButtonTitles:nil];
    [alert show];
  }
}

#pragma mark - UITableView Delegate

- (void)tableView:(UITableView *)tableView
  willDisplayCell:(UITableViewCell *)cell
forRowAtIndexPath:(NSIndexPath *)indexPath {
  if (indexPath.section == 1) {
    // Show/hide the disclosure indicator on the "Measure Latency" cells.
    cell.accessoryType = (_client.isConnected ?
                          UITableViewCellAccessoryDisclosureIndicator :
                          UITableViewCellAccessoryNone);
  }
}

- (NSIndexPath *)tableView:(UITableView *)tableView
  willSelectRowAtIndexPath:(NSIndexPath *)indexPath {
  if (indexPath.section == 0 && indexPath.row == 0) {
    // "Clock Sync"
    NSError *error = nil;
    if (![_client checkConnectionWithError:&error] ||
        ![_client syncClocksWithError:&error]) {
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error"
                                                        error:error];
      [alert show];
    }
    [[WALTLogger sessionLogger] appendFormat:@"SYNC\t%lld\t%lld\n",
        _client.minError, _client.maxError];
    return nil;
  } else if (indexPath.section == 1 && !_client.isConnected) {
    // "Measure Latency"
    return nil;
  }
  
  return indexPath;
}
@end
