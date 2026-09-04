#!/usr/bin/env python3
"""Minimal jailed SFTP server for sync-player emulator repro.

- user/password auth (test/test123), serves ONLY /tmp/opencode/sftp-media
- host key persisted to /tmp/opencode/sftp_host_key.pem (stable fingerprint for app TOFU)
- bind 127.0.0.1:2222 (emulator reaches host loopback via 10.0.2.2)
- implements list_folder/open/stat/lstat (+mkdir/rmdir/remove/rename) against local fs, path-jailed
"""
import os
import posixpath
import socket
import threading
import time
import errno

import paramiko
from paramiko import RSAKey
from paramiko.sftp import SFTP_NO_SUCH_FILE, SFTP_PERMISSION_DENIED
from paramiko.sftp_attr import SFTPAttributes
from paramiko.sftp_handle import SFTPHandle
from paramiko.sftp_server import SFTPServer
from paramiko.sftp_si import SFTPServerInterface

HOST_KEY_PATH = "/tmp/opencode/sftp_host_key.pem"
ROOT = "/tmp/opencode/sftp-media"
USER = "test"
PASSWORD = "test123"
BIND_HOST, BIND_PORT = "127.0.0.1", 2222

if not os.path.exists(HOST_KEY_PATH):
    key = RSAKey.generate(2048)
    key.write_private_key_file(HOST_KEY_PATH)
    os.chmod(HOST_KEY_PATH, 0o600)
HOST_KEY = RSAKey.from_private_key_file(HOST_KEY_PATH)


def _sftp_error(exc: OSError):
    if isinstance(exc, PermissionError):
        return SFTP_PERMISSION_DENIED
    if isinstance(exc, FileNotFoundError):
        return SFTP_NO_SUCH_FILE
    return SFTP_NO_SUCH_FILE


class LocalSFTPHandle(SFTPHandle):
    def stat(self):
        try:
            return SFTPAttributes.from_stat(os.fstat(self.readfile.fileno()))
        except OSError:
            return SFTP_NO_SUCH_FILE


class JailedSFTPInterface(SFTPServerInterface):
    """Maps virtual SFTP paths (root "/") under ROOT, refusing escapes."""

    def _jail(self, path: str) -> str:
        p = path or "/"
        if not p.startswith("/"):
            p = "/" + p
        p = posixpath.normpath(p)
        real = os.path.normpath(os.path.join(ROOT, p.lstrip("/")))
        if real != ROOT and not real.startswith(ROOT + os.sep):
            raise FileNotFoundError(path)
        return real

    def _realpath(self, path):
        return self._jail(path)

    def canonicalize(self, path):
        return posixpath.normpath(path or "/")

    def list_folder(self, path):
        real = self._jail(path)
        try:
            names = os.listdir(real)
        except OSError as exc:
            return _sftp_error(exc)
        attrs = []
        for name in names:
            try:
                attr = SFTPAttributes.from_stat(os.lstat(os.path.join(real, name)))
            except OSError:
                continue
            attr.filename = name
            attrs.append(attr)
        return attrs

    def open(self, path, flags, attr):
        real = self._jail(path)
        mode = getattr(attr, "st_mode", None) or 0o666
        try:
            fd = os.open(real, flags, mode)
        except OSError as exc:
            return _sftp_error(exc)
        if (flags & os.O_ACCMODE) == os.O_RDONLY:
            strflag = "rb"
        elif (flags & os.O_ACCMODE) == os.O_WRONLY:
            strflag = "ab" if (flags & os.O_APPEND) else "wb"
        else:
            strflag = "a+b" if (flags & os.O_APPEND) else "r+b"
        try:
            f = os.fdopen(fd, strflag)
        except OSError as exc:
            return _sftp_error(exc)
        handle = LocalSFTPHandle(flags)
        handle.readfile = f
        if (flags & os.O_ACCMODE) != os.O_RDONLY:
            handle.writefile = f
        return handle

    def stat(self, path):
        try:
            return SFTPAttributes.from_stat(os.stat(self._jail(path)))
        except OSError as exc:
            return _sftp_error(exc)

    def lstat(self, path):
        try:
            return SFTPAttributes.from_stat(os.lstat(self._jail(path)))
        except OSError as exc:
            return _sftp_error(exc)

    def remove(self, path):
        try:
            os.remove(self._jail(path))
            return paramiko.sftp.SFTP_OK
        except OSError as exc:
            return _sftp_error(exc)

    def mkdir(self, path, attr):
        try:
            os.mkdir(self._jail(path), getattr(attr, "st_mode", None) or 0o777)
            return paramiko.sftp.SFTP_OK
        except OSError as exc:
            return _sftp_error(exc)

    def rmdir(self, path):
        try:
            os.rmdir(self._jail(path))
            return paramiko.sftp.SFTP_OK
        except OSError as exc:
            return _sftp_error(exc)

    def rename(self, oldpath, newpath):
        try:
            os.rename(self._jail(oldpath), self._jail(newpath))
            return paramiko.sftp.SFTP_OK
        except OSError as exc:
            return _sftp_error(exc)


class SyncPlayerSSHServer(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        if (username, password) == (USER, PASSWORD):
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED


def handle(conn, addr):
    transport = paramiko.Transport(conn)
    transport.add_server_key(HOST_KEY)
    transport.set_subsystem_handler("sftp", SFTPServer, JailedSFTPInterface)
    try:
        transport.start_server(server=SyncPlayerSSHServer())
        while transport.is_active():
            time.sleep(1)
    except Exception as exc:  # noqa: BLE001
        print(f"[{addr}] session error: {exc}", flush=True)
    finally:
        try:
            transport.close()
        except Exception:
            pass


def main():
    print(f"serving {ROOT} as '{USER}' on {BIND_HOST}:{BIND_PORT}", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((BIND_HOST, BIND_PORT))
    sock.listen(8)
    while True:
        conn, addr = sock.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
