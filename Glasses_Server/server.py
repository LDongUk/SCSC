# coding: utf-8

import bluetooth as bt
from picamera import PiCamera
import crosswalk
import cv2
import sys
import os
import time

name = 'bt_server'
uuid = '3fef2a50-5c7f-11e7-9598-0800200c9a66'

def detect(picnum):
    c = PiCamera()
    c.resolution = (800, 480)
    c.capture_sequence(['./tmp/%d.jpg' % n for n in range(picnum)])
    c.close()

    print("cam done")

    pic_arr = [cv2.imread('./tmp/%d.jpg' % n) for n in range(picnum)]
    return crosswalk.detector(pic_arr)

sock = bt.BluetoothSocket(bt.RFCOMM)
port = bt.PORT_ANY
sock.bind(('', port))
print('Listening : port ' + str(port))

sock.listen(1)
port = sock.getsockname()[1]

bt.advertise_service(sock, 'BtServer',
                 service_id = uuid,
                 service_classes = [uuid, bt.SERIAL_PORT_CLASS],
                 profiles = [bt.SERIAL_PORT_PROFILE])


while True:
    insock, addr = sock.accept()
    print('Got connection with ', addr)
    
    
    cmd = insock.recv(1024)

    if cmd == b'PHOTO':
        print('PHOTO')
        filename = './tmp/cam.jpg'

        # 사진 찍기
        c = PiCamera()
        c.resolution = (320, 240)
        c.capture(filename)
        c.close()

        # 전송
        filelen = os.path.getsize(filename)
        insock.send('%d' % filelen)
        packet = 1
        print('filelen: ' + str(filelen))

        msg = insock.recv(1024)
        # TODO: msg 검증
        print(msg)
        
        with open(filename, 'rb') as f:
            packet = f.read(1024)
            while packet:
                insock.sendall(packet)
                print('.', end='')
                packet = f.read(1024)
            print('done')

    elif cmd == b'WALK':
        print('WALK')
        insock.settimeout(0.1)

        while True:
            # run D's code
            try:
                dresult = detect(3)
                print(dresult)
                if dresult[0] == True:
                    if dresult[1] == 'S':
                        insock.send('STRIGHT')
                    elif dresult[1] == 'L':
                        insock.send('LEFT')
                    elif dresult[1] == 'R':
                        insock.send('RIGHT')
                else:
                    insock.send('NONE')
            except:
                print("except")

            try:
                cmd = insock.recv(1024)
                if cmd == b'END':
                    break
            except:
                pass

    else:
        print('Invaled command: ' + str(cmd))
    
    insock.settimeout(None)
    insock.close()
    print('disconnected')
    
