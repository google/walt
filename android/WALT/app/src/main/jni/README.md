# Clock Synchronization

How it works

## Step 1 - rough sync

        T0 = current_time()
        Tell the remote to zero clock.
        Wait for confirmation from remote
        maxE = current_time() - T0
        All further local time is measured from T0


After this step we are sure that the remote clock lags behind the local clock by
some value E. And we know that E >= 0 because remote was zeroed *after* we
zeroed the local time (recored T0). And also E<= maxE. So 0 = minE < E < maxE.


## Step 2 - find better lower bound - `minE`

Send some messages from local to remote, note the time right before sending the
message (denote it as `t_local`) and have the remote reply with his timestamps
of when it received the messages according to his clock that lags by unknown
positive value `E` behind the local clock, denote it by `t_remote`.


        t_remote = t_local - E + travel_time
        E = t_local - t_remote + travel_time > t_local - t_remote
        since travel_time > 0
        E > t_local - t_remote

        set minE to max(minE, t_local - t_remote)
        Repeat

We need to first send a bunch of messages with some random small delays, and
only after that get the remote timestamps for all of them. This helps deal with
unwanted buffering and delay added by the kernel of hardware in the outgoing
direction.

## Step 3 - find better upper bound `maxE`

Same idea, but in the opposite direction. Remote device sends us messages and
then the timestamps according to his clock of when they were sent. We record the
local timestamps when we receive them.

    t_local = t_remote + E + travel_time
    E = t_local - t_remote - travel time < t_local - t_remote
    set maxE = min(maxE, t_local - t_remote)
    Repeat

## Comparison with NTP

NTP measures the mean travel_time (latency) and assumes it to be symmetric - the
same in both directions. If the symmetry assumption is broken, there is no way
to detect this. Both, systematic asymmetry in latency and clock difference would
result in exactly the same observations -
[explanation here](http://cs.stackexchange.com/questions/103/clock-synchronization-in-a-network-with-asymmetric-delays).

In our case the latency can be relatively small compared to network, but is
likely to be asymmetric due to the asymmetric nature of USB. The algorithm
described above guarantees that the clock difference is within the measured
bounds `minE < E < maxE`, though the resulting interval `deltaE = maxE - minE`
can be fairly large compared to synchronization accuracy of NTP on a network
with good latency symmetry.

Observed values for `deltaE`
 - Linux desktop machine (HP Z420), USB2 port: ~100us
 - Same Linux machine, USB3 port: ~800us
 - Nexus 5 ~100us
 - Nexus 7 (both the old and the newer model) ~300us
 - Samsung Galaxy S3 ~150us



## Implementation notes

General
 - All times in this C code are recored in microseconds, unless otherwise
   specified.
 - The timestamped messages are all single byte.

USB communication
 - USB hierarchy recap: USB device has several interfaces. Each interface has
   several endpoints. Endpoints are directional IN = data going into the host,
   OUT = data going out of the host. To get data from the device via an IN
   endpoint, we must query it.
 - There are two types of endpoints - BULK and INTERRUPT. For our case it's not
   really important. Currently all the comms are done via a BULK interface
   exposed when you compile Teensy code in "Serial".
 - All the comms are done using the Linux API declared in linux/usbdevice_fs.h
 - The C code can be compiled for both Android JNI and Linux.
 - The C code is partially based on the code of libusbhost from the Android OS
   core code, but does not use that library because it's an overkill for our
   needs.

## There are two ways of communicating via usbdevice_fs

        // Async way
        ioctl(fd, USBDEVFS_SUBMITURB, urb);
        // followed by
        ioctl(fd, USBDEVFS_REAPURB, &urb); // Blocks until there is a URB to read.

        // Sync way
        struct usbdevfs_bulktransfer  ctrl;
        ctrl.ep = endpoint;
        ctrl.len = length;
        ctrl.data = buffer;
        ctrl.timeout = timeout; // [milliseconds] Will timeout if there is nothing to read
        int ret = ioctl(fd, USBDEVFS_BULK, &ctrl);



