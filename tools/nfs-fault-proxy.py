#!/usr/bin/env python3
"""Local TCP fault proxy for NfsRecoveryLiveTest; use a disposable NFSv4 export."""

import argparse
import selectors
import socket
import threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("upstream", help="NFS server address (port 2049)")
args = parser.parse_args()
connections = set()
lock = threading.Lock()
offline = False


def listener(port):
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen()
    return server


def serve(client):
    with lock:
        if offline:
            client.close()
            return
    try:
        upstream = socket.create_connection((args.upstream, 2049), 3)
    except OSError:
        client.close()
        return
    with lock:
        connections.update((client, upstream))
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(client, selectors.EVENT_READ, upstream)
            selector.register(upstream, selectors.EVENT_READ, client)
            while True:
                for key, _ in selector.select():
                    data = key.fileobj.recv(1048576)
                    if not data:
                        return
                    key.data.sendall(data)
    except OSError:
        pass
    finally:
        with lock:
            connections.discard(client)
            connections.discard(upstream)
        client.close()
        upstream.close()


def control(server):
    global offline
    while True:
        client, _ = server.accept()
        with client:
            command = client.recv(100).strip()
            with lock:
                if command == b"OFFLINE":
                    offline = True
                elif command == b"ONLINE":
                    offline = False
                if command in (b"DROP", b"OFFLINE"):
                    for connection in tuple(connections):
                        try:
                            connection.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
            client.sendall(b"OK\n")


# Bind both listeners before accepting requests, so setup failures are immediate.
server = listener(2049)
control_server = listener(32049)
threading.Thread(target=control, args=(control_server,), daemon=True).start()
print(f"127.0.0.1:2049 -> {args.upstream}:2049; control 127.0.0.1:32049", flush=True)
while True:
    client, _ = server.accept()
    threading.Thread(target=serve, args=(client,), daemon=True).start()
