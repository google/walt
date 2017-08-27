import numpy


def screen_stats(blinker_file_name, sensor_file_name):

    sensor_data = numpy.loadtxt(sensor_file_name)
    blinker_data = numpy.loadtxt(blinker_file_name)

    # Convert all times to milliseconds
    t_sensor = sensor_data[:, 0] * 1e3
    t_vsync = blinker_data / 1e3

    # Throw away any sensor timestamps earlier than the first blink
    # this may happen if the operator attached the sensor after
    # running the command. But this should be avoided.
    skip_sensor = sum(t_sensor < t_vsync[0])
    if(skip_sensor):
        t_sensor = t_sensor[skip_sensor:]
        print('Skipped first %d readings from the sensor' % skip_sensor)

    # Get only the common size and skip the first blink, it's often weird.
    length = min(len(t_sensor), len(t_vsync))
    t_sensor = t_sensor[1:length]
    t_vsync = t_vsync[1:length]

    # Shift time so that first time point is 0
    t0 = min(t_vsync)
    t_sensor = t_sensor - t0
    t_vsync = t_vsync - t0

    dt = t_sensor - t_vsync

    # Look at even and odd transitions separately - black <-> white.
    dt_even = dt[0::2]
    dt_odd = dt[1::2]

    print('')
    print('dt = array([' + ', '.join('%0.2f' % x for x in dt) + '])')
    print('')
    print('Screen response times [ms]')
    print('Even: median %0.1f ms, stdev %0.2f ms' %
          (numpy.median(dt_even), numpy.std(dt_even)))
    print('Odd:  median %0.1f ms, stdev %0.2f ms' %
          (numpy.median(dt_odd), numpy.std(dt_odd)))


# Debug & test
if __name__ == '__main__':

    fname = '/tmp/WALT_2016_06_22__1739_21_'
    blinker_file_name = fname + 'evtest.log'
    sensor_file_name = fname + 'laser.log'

    screen_stats(blinker_file_name, sensor_file_name)
