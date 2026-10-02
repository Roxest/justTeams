"""Read-only loopback login of the known synthetic owner; no auth or gameplay commands."""
import hashlib
import json
import socket
import struct
import time
import uuid
import zlib
from rig import ROOT,marked,send

marked()
NAME='JTMoneyOwner'
OWNER=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+NAME).encode()).digest(),version=3)
compression=None

def varint(value):
    result=bytearray()
    while True:
        part=value&127;value >>=7;result.append(part|(128 if value else 0))
        if not value:return bytes(result)

def parseint(data,offset=0):
    result=0
    for shift in range(0,35,7):
        part=data[offset];offset+=1;result|=(part&127)<<shift
        if not part&128:return result,offset
    raise ValueError('Invalid VarInt')

def text(value):
    data=value.encode();return varint(len(data))+data

def exact(connection,count):
    result=bytearray()
    while len(result)<count:
        chunk=connection.recv(count-len(result))
        if not chunk:raise EOFError()
        result.extend(chunk)
    return bytes(result)

def read(connection):
    length=bytearray()
    while True:
        part=exact(connection,1);length+=part
        if not part[0]&128:break
    size,_=parseint(length);assert size<8_000_000
    body=exact(connection,size)
    if compression is not None:
        inflated,offset=parseint(body);body=zlib.decompress(body[offset:]) if inflated else body[offset:]
    packet,offset=parseint(body);return packet,body[offset:]

def write(connection,packet,payload=b''):
    body=varint(packet)+payload
    if compression is not None:
        body=varint(len(body))+zlib.compress(body) if len(body)>=compression else b'\0'+body
    connection.sendall(varint(len(body))+body)

# Remove only the old synthetic Carpet identity; its disconnect override otherwise masks transport.
send('player JTMoneyOwner kill');time.sleep(.5)
result={'syntheticOwner':str(OWNER),'transport':'127.0.0.1:25655','rung':'state','packets':[]}
phase='login';deadline=time.monotonic()+20
with socket.create_connection(('127.0.0.1',25655),timeout=10) as connection:
    connection.settimeout(10)
    write(connection,0,varint(767)+text('127.0.0.1')+struct.pack('>H',25655)+varint(2))
    write(connection,0,text(NAME)+OWNER.bytes)
    while time.monotonic()<deadline:
        try:packet,payload=read(connection)
        except EOFError:result['serverClosedTransport']=True;break
        result['packets'].append({'phase':phase,'id':packet,'bytes':len(payload)})
        if b'inventory recovery required' in payload:
            result['nativeQuarantineReasonReceived']=True
        if phase=='login':
            if packet==3:compression,_=parseint(payload)
            elif packet==2:
                result['serverProfile']=str(uuid.UUID(bytes=payload[:16]));write(connection,3);phase='configuration'
            elif packet==4:
                request,_=parseint(payload);write(connection,2,varint(request)+b'\0')
            elif packet==0:result['loginDisconnect']=payload.decode('utf-8','replace')[:240];break
        elif phase=='configuration':
            if packet==14:write(connection,7,varint(0))
            elif packet==3:write(connection,3);phase='play'
            elif packet==4:write(connection,4,payload)
            elif packet==5:write(connection,5,payload)
            elif packet==2:
                result['configurationDisconnect']=True
                if not result.get('nativeQuarantineReasonReceived'):result['otherReason']=payload.decode('utf-8','replace')[:240]
        elif phase=='play':
            if result.get('nativeQuarantineReasonReceived'):
                try:read(connection)
                except EOFError:result['serverClosedTransport']=True
                break
(ROOT/'qa-quarantine-protocol.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps({key:value for key,value in result.items() if key!='packets'}))
assert result.get('serverProfile')==str(OWNER), 'Login identity mismatch'
assert result.get('nativeQuarantineReasonReceived') and result.get('serverClosedTransport'), 'Native quarantine disconnect not proven'
