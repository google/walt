import numpy


def screen_stats(blinker_file_name, sensor_file_name):

    sensor_data = numpy.loadtxt(sensor_file_name)
    blinker_data = numpy.loadtxt(blinker_file_name)

    # Convert all times to milliseconds
    t_sensor = sensor_data[:, 0] * 1000
    t_blinker = blinker_data / 1e3

    # Throw away any sensor timestamps earlier than the first blink
    # this may happen if the operator attached the sensor after
    # running the command. But this should be avoided.
    skip_sensor = sum(t_sensor < t_blinker[0])
    if(skip_sensor):
        t_sensor = t_sensor[skip_sensor:]
        print "Skipped first %d readings from the sensor" % skip_sensor

    # Get only the common size
    length = min(len(t_sensor), len(t_blinker))
    t_sensor = t_sensor[1:length]
    t_blinker = t_blinker[1:length]

    # Shift time so that first time point is 0
    t0 = min(t_blinker)
    t_sensor = t_sensor - t0
    t_blinker = t_blinker - t0

    dt = t_sensor - t_blinker
    print('t = array([' + ', '.join(map(str, dt)) + '])')

    print("Screen response [ms] median: %0.1f, stdev: %0.2f" %
            (numpy.median(dt), numpy.std(dt)))


# Debug & test
if __name__ == '__main__':

    fname = '/tmp/WALT_2016_06_22__1739_21_'
    blinker_file_name = fname + 'evtest.log'
    sensor_file_name = fname + 'laser.log'

    screen_stats(blinker_file_name, sensor_file_name)