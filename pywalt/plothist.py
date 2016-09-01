#
# Utility script to plot a histogram of tap latency results
# run in iPython pylab mode using the -i flag
# ipython --pylab
# %run -i plothist.py
# show()

# Copy / paste data from walt.py output here
dt_down += [32.97, 21.91, 23.06, 21.72, 67.31, 22.71, 29.52, 39.83, 38.55, 24.35, 30.14, 30.52, 38.66, 21.43, 28.69, 29.57, 21.93, 22.64, 38.77]
dt_up += [15.86, 17.36, 15.93, 21.32, 20.16, 18.95, 14.13, 6.05, 17.58, 12.21, 13.23, 16.17, 19.14, 11.35]


dt_down = array(dt_down)
dt_up = array(dt_up)


# Filter out number that are definitely garbage

dt_down = dt_down[dt_down < 150]
dt_up = dt_up[dt_up < 150]

hist([dt_down, dt_up])

xlabel('tap latency [ms]')
ylabel('Count')
grid(True)
legend([
    'tap down N=%d med=%0.1f' % (len(dt_down), median(dt_down)),
    'tap up N=%d med=%0.1f' % (len(dt_up), median(dt_up)),
    ])
title('Tap latency')