#!/usr/bin/env python

from setuptools import setup, find_packages

setup(
    name='pywalt',
    entry_points={
        'console_scripts': (
            'walt = pywalt.walt:main',
        ),
    },
    install_requires=['pyserial'],
    packages=find_packages(),
    description='WALT Latency Timer',
    license='Apache 2.0',
    url='https://github.com/google/walt',
)
