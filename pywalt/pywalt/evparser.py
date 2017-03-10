import re
from numpy import array

TIME = 'time'
VALUE = 'value'
AXIS = 'axis'

re_xy = re.compile(r'.*time (?P<time>\d+\.\d+), type \d+ \(EV_ABS\), code \d+ \(ABS_(?P<axis>[XY])\), value (?P<value>\d+)')
re_tap = re.compile(r'.*time (?P<time>\d+\.\d+), type \d+ \(EV_KEY\), code \d+ \(BTN_TOUCH\), value (?P<value>\d+)')


def load_xy(fname):
    with open(fname, 'rt') as f:
        match_iter = (re_xy.search(line) for line in f)
        events = [m.groupdict() for m in match_iter if m]

    x = array([int(e[VALUE]) for e in events if e[AXIS] == 'X'])
    tx = array([float(e[TIME]) for e in events if e[AXIS] == 'X'])

    y = array([int(e[VALUE]) for e in events if e[AXIS] == 'Y'])
    ty = array([float(e[TIME]) for e in events if e[AXIS] == 'Y'])

    return (tx, x, ty, y)


def parse_tap_line(line):
    m = re_tap.search(line)
    if not m:
        return None

    t = float(m.group(TIME))
    val = int(m.group(VALUE))
    return (t, val)
