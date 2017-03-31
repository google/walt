#!/usr/bin/env python
#
# Copyright 2017 The Chromium OS Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Runs a server that receives log uploads from the WALT app
Usage example:
    $ python main.py
"""

import time
import os

try:
    from bottle import route, template, run, request, static_file
except:
    print('Could not import bottle! Please install bottle, e.g. pip install bottle')
    raise

@route('/')
def index():
    if not os.path.isdir('logs/'):
        return 'No files uploaded yet'
    filenames = []
    for file in os.listdir('logs/'):
        if file.endswith('.txt'):
            filenames.append(file)
    return template('make_table', filenames=filenames)


@route('/logs/<filename>')
def static(filename):
    return static_file(filename, root='logs')


@route('/upload', method='POST')
def upload():
    body = request.body.getvalue()
    request.body.close()
    filename = 'logs/' + str(int(time.time()*1000)) + '.txt'
    if not os.path.exists(os.path.dirname(filename)):
        try:
            os.makedirs(os.path.dirname(filename))
        except OSError as e:
            if e.errno != errno.EEXIST:
                raise
    with open(filename, 'w') as file:
        file.write(body)
    return 'success'


run(host='localhost', port=8080)

