from setuptools import setup, find_packages

setup(
    name='pywalt',
    entry_points={
        'console_scripts': (
            'walt = pywalt.walt:main',
        ),
    },
    packages=find_packages(),
    description='WALT Latency Timer',
    license='Apache 2.0',
    url='http://github.com/google/walt',
)
