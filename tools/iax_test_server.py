#!/usr/bin/env python3
"""
Минимальный IAX2-сервер для проверки клиента без Asterisk.

  python iax_test_server.py --port 4569 --user 100:secret

Номера:
  600  эхо (возвращает ваш голос)
  700  тон 440 Гц
  486  «занято»
  800  сервер сбрасывает вызов и через 2 с перезванивает вам (проверка входящих)

Команды в консоли: "call <user>" — позвонить зарегистрированному пользователю,
"hangup" — завершить все вызовы, "quit" — выход.
"""
import argparse
import hashlib
import math
import queue
import random
import select
import socket
import struct
import sys
import threading
import time

# Типы кадров и команды
DTMF, VOICE, CONTROL, IAX = 1, 2, 4, 6
NEW, PING, PONG, ACK, HANGUP, REJECT, ACCEPT, AUTHREQ, AUTHREP, INVAL = 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
LAGRQ, LAGRP, REGREQ, REGAUTH, REGACK, REGREJ, REGREL = 11, 12, 13, 14, 15, 16, 17
POKE, CALLTOKEN = 30, 40
C_HANGUP, C_RINGING, C_ANSWER, C_BUSY = 1, 3, 4, 5
NO_ACK_SEQ = {ACK}
# IE
IE_CALLED_NUMBER, IE_CALLING_NUMBER, IE_CALLING_NAME, IE_USERNAME = 0x01, 0x02, 0x04, 0x06
IE_CAPABILITY, IE_FORMAT, IE_VERSION, IE_AUTHMETHODS, IE_CHALLENGE = 0x08, 0x09, 0x0b, 0x0e, 0x0f
IE_MD5_RESULT, IE_REFRESH, IE_CAUSE, IE_CAUSECODE, IE_CALLTOKEN = 0x10, 0x13, 0x16, 0x2a, 0x36
ULAW, ALAW = 0x4, 0x8

IAX_NAMES = {NEW: "NEW", PING: "PING", PONG: "PONG", ACK: "ACK", HANGUP: "HANGUP", REJECT: "REJECT",
             ACCEPT: "ACCEPT", AUTHREQ: "AUTHREQ", AUTHREP: "AUTHREP", INVAL: "INVAL", LAGRQ: "LAGRQ",
             LAGRP: "LAGRP", REGREQ: "REGREQ", REGAUTH: "REGAUTH", REGACK: "REGACK", REGREJ: "REGREJ",
             REGREL: "REGREL", POKE: "POKE", CALLTOKEN: "CALLTOKEN"}
CTRL_NAMES = {C_HANGUP: "HANGUP", C_RINGING: "RINGING", C_ANSWER: "ANSWER", C_BUSY: "BUSY"}


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def ies_parse(data):
    out, p = {}, 0
    while p + 2 <= len(data):
        t, n = data[p], data[p + 1]
        out.setdefault(t, data[p + 2:p + 2 + n])
        p += 2 + n
    return out


def ie(t, v):
    if isinstance(v, str):
        v = v.encode()
    return bytes([t, len(v)]) + v


def ie16(t, v):
    return ie(t, struct.pack(">H", v))


def ie32(t, v):
    return ie(t, struct.pack(">I", v))


def ulaw(sample):
    sign = 0
    if sample < 0:
        sample, sign = -sample, 0x80
    sample = min(sample, 32635) + 0x84
    exp, mask = 7, 0x4000
    while exp > 0 and not (sample & mask):
        exp, mask = exp - 1, mask >> 1
    return ~(sign | (exp << 4) | ((sample >> (exp + 3)) & 0x0f)) & 0xff


def alaw(sample):
    if sample >= 0:
        sign = 0x80
    else:
        sign, sample = 0, -sample - 1
    sample = min(sample, 32767)
    if sample < 256:
        out = sample >> 4
    else:
        exp, mask = 7, 0x4000
        while exp > 1 and not (sample & mask):
            exp, mask = exp - 1, mask >> 1
        out = (exp << 4) | ((sample >> (exp + 3)) & 0x0f)
    return (sign | out) ^ 0x55


class Call:
    def __init__(self, server, addr, kind):
        self.callno = server.alloc_callno()
        self.addr = addr
        self.kind = kind          # reg | in | out | poke
        self.peer = 0
        self.oseq = 0
        self.iseq = 0
        self.start = time.monotonic()
        self.last_ts = 0
        self.pending = []         # [oseq, bytes, sent_at, tries]
        self.challenge = ""
        self.user = None
        self.cmd = 0
        self.ext = ""
        self.fmt = ULAW
        self.mode = None          # echo | tone
        self.answered = False
        self.voice_started = False
        self.voice_ts = -20
        self.tone_phase = 0.0
        self.rx_voice = 0
        self.closing = False
        self.last_ping = time.monotonic()
        self.timers = []          # (time, fn)


class Server:
    def __init__(self, host, port, users):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind((host, port))
        self.users = users
        self.regs = {}            # user -> addr
        self.calls = {}
        self.secret = random.getrandbits(64)
        self.cmds = queue.Queue()
        self.last_poke = time.monotonic()
        log(f"IAX2 тестовый сервер слушает {host}:{port}, пользователи: {', '.join(users)}")

    # -------------------------------------------------------------- utils
    def alloc_callno(self):
        while True:
            n = random.randint(2, 0x7fff)
            if n not in self.calls:
                return n

    def token_for(self, addr):
        return hashlib.md5(f"{self.secret}{addr}".encode()).hexdigest()[:16]

    def raw_send(self, data, addr):
        self.sock.sendto(data, addr)

    def frame(self, src, dst, ts, oseq, iseq, ftype, sub, payload=b"", retrans=False):
        return struct.pack(">HHIBBBB", 0x8000 | src, (0x8000 if retrans else 0) | dst, ts & 0xffffffff,
                           oseq & 0xff, iseq & 0xff, ftype, sub) + payload

    def describe(self, ftype, sub, payload):
        if ftype == IAX:
            name = "IAX/" + IAX_NAMES.get(sub, str(sub))
            if payload:
                name += " " + str({hex(k): (v.decode(errors="replace") if all(32 <= b < 127 for b in v) else v.hex())
                                   for k, v in ies_parse(payload).items()})
            return name
        if ftype == CONTROL:
            return "CONTROL/" + CTRL_NAMES.get(sub, str(sub))
        if ftype == DTMF:
            return "DTMF/" + chr(sub)
        return f"TYPE{ftype}/{sub}"

    def send_full(self, c, ftype, sub, payload=b"", ts=None):
        now_ts = int((time.monotonic() - c.start) * 1000)
        if ts is None:
            ts = max(now_ts, c.last_ts + 1)
        c.last_ts = max(c.last_ts, ts)
        data = self.frame(c.callno, c.peer, ts, c.oseq, c.iseq, ftype, sub, payload)
        c.pending.append([c.oseq, data, time.monotonic(), 0])
        c.oseq = (c.oseq + 1) & 0xff
        if ftype != VOICE:
            log(f">> {c.addr} #{c.callno} {self.describe(ftype, sub, payload)}")
        self.raw_send(data, c.addr)

    def send_ack(self, c, ts):
        self.raw_send(self.frame(c.callno, c.peer, ts, c.oseq, c.iseq, IAX, ACK), c.addr)

    def destroy(self, c):
        self.calls.pop(c.callno, None)

    def close(self, c):
        c.closing = True
        c.close_at = time.monotonic() + 3

    # -------------------------------------------------------------- receive
    def on_packet(self, data, addr):
        if len(data) < 4:
            return
        w0 = struct.unpack(">H", data[:2])[0]
        if not w0 & 0x8000:
            if w0 == 0:
                return
            ts16 = struct.unpack(">H", data[2:4])[0]
            for c in self.calls.values():
                if c.peer == w0 and c.addr == addr:
                    self.on_voice(c, data[4:])
                    return
            return
        if len(data) < 12:
            return
        src, dst, ts, oseq, iseq, ftype, sub = struct.unpack(">HHIBBBB", data[:12])
        src &= 0x7fff
        dst &= 0x7fff
        payload = data[12:]
        if sub & 0x80:
            sub = 1 << (sub & 0x7f)

        if dst == 0:
            existing = next((c for c in self.calls.values() if c.peer == src and c.addr == addr), None)
            if existing is None:
                self.on_new_transaction(addr, src, ts, oseq, ftype, sub, payload)
                return
            c = existing
        else:
            c = self.calls.get(dst)
            if c is None or c.addr != addr:
                return
        if c.peer == 0:
            c.peer = src
        # неявное подтверждение
        c.pending = [p for p in c.pending if not (1 <= ((iseq - p[0]) & 0xff) <= 127)]
        if ftype == IAX and sub == ACK:
            return
        if ftype != VOICE:
            log(f"<< {addr} #{c.callno} {self.describe(ftype, sub, payload)} o={oseq} i={iseq}")
        if oseq != c.iseq:
            if 1 <= ((c.iseq - oseq) & 0xff) <= 127:
                self.send_ack(c, ts)
            return
        c.iseq = (c.iseq + 1) & 0xff
        self.send_ack(c, ts)
        if c.closing:
            return
        self.on_frame(c, ts, ftype, sub, payload)

    def on_new_transaction(self, addr, src, ts, oseq, ftype, sub, payload):
        if ftype != IAX or sub not in (NEW, REGREQ, REGREL):
            if ftype == IAX and sub in (PONG, ACK):
                return
            log(f"<< {addr} неизвестная транзакция {self.describe(ftype, sub, payload)}")
            return
        ies = ies_parse(payload)
        log(f"<< {addr} {self.describe(ftype, sub, payload)} o={oseq}")
        token = ies.get(IE_CALLTOKEN)
        if token is None or token == b"":
            # просим повторить с токеном
            data = self.frame(1, src, ts, 0, oseq + 1, IAX, CALLTOKEN, ie(IE_CALLTOKEN, self.token_for(addr)))
            log(f">> {addr} IAX/CALLTOKEN")
            self.raw_send(data, addr)
            return
        if token.decode(errors="replace") != self.token_for(addr):
            log("неверный CALLTOKEN")
            return
        c = Call(self, addr, "reg" if sub in (REGREQ, REGREL) else "in")
        c.peer = src
        c.iseq = (oseq + 1) & 0xff
        c.cmd = sub
        self.calls[c.callno] = c
        self.send_ack(c, ts)
        user = (ies.get(IE_USERNAME) or b"").decode()
        c.user = user
        c.challenge = str(random.randint(100000000, 999999999))
        if sub == NEW:
            c.ext = (ies.get(IE_CALLED_NUMBER) or b"").decode()
            cap = struct.unpack(">I", ies[IE_CAPABILITY])[0] if IE_CAPABILITY in ies else ULAW
            fmt = struct.unpack(">I", ies[IE_FORMAT])[0] if IE_FORMAT in ies else 0
            c.fmt = fmt if fmt in (ULAW, ALAW) else (ULAW if cap & ULAW else ALAW)
            self.send_full(c, IAX, AUTHREQ, ie16(IE_AUTHMETHODS, 2) + ie(IE_CHALLENGE, c.challenge) + ie(IE_USERNAME, user))
        else:
            self.send_full(c, IAX, REGAUTH, ie16(IE_AUTHMETHODS, 2) + ie(IE_CHALLENGE, c.challenge) + ie(IE_USERNAME, user))

    def check_md5(self, c, ies):
        pw = self.users.get(c.user)
        got = (ies.get(IE_MD5_RESULT) or b"").decode()
        return pw is not None and got == hashlib.md5((c.challenge + pw).encode()).hexdigest()

    def on_frame(self, c, ts, ftype, sub, payload):
        ies = ies_parse(payload) if ftype == IAX else {}
        if ftype == IAX:
            if sub in (REGREQ, REGREL) and c.kind == "reg":
                if not self.check_md5(c, ies):
                    self.send_full(c, IAX, REGREJ, ie(IE_CAUSE, "Registration Refused") + ie(IE_CAUSECODE, b"\x1d"))
                    self.close(c)
                    return
                refresh = struct.unpack(">H", ies[IE_REFRESH])[0] if IE_REFRESH in ies else 60
                if sub == REGREQ:
                    self.regs[c.user] = c.addr
                    log(f"** {c.user} зарегистрирован с {c.addr}")
                else:
                    self.regs.pop(c.user, None)
                    log(f"** {c.user} снял регистрацию")
                self.send_full(c, IAX, REGACK, ie(IE_USERNAME, c.user) + ie16(IE_REFRESH, refresh))
                self.close(c)
            elif sub == AUTHREP and c.kind == "in":
                if not self.check_md5(c, ies):
                    self.send_full(c, IAX, REJECT, ie(IE_CAUSE, "Authentication failed"))
                    self.close(c)
                    return
                self.route(c)
            elif sub == HANGUP:
                log(f"** вызов #{c.callno} завершён клиентом: {(ies.get(IE_CAUSE) or b'').decode()}")
                self.destroy(c)
            elif sub == REJECT:
                log(f"** вызов #{c.callno} отклонён клиентом")
                self.destroy(c)
            elif sub == ACCEPT and c.kind == "out":
                fmt = struct.unpack(">I", ies[IE_FORMAT])[0] if IE_FORMAT in ies else ULAW
                c.fmt = fmt
                log(f"** клиент принял вызов, кодек 0x{fmt:x}")
            elif sub == PING:
                self.send_full(c, IAX, PONG, ts=ts)
            elif sub == LAGRQ:
                self.send_full(c, IAX, LAGRP, ts=ts)
            elif sub == PONG and c.kind == "poke":
                self.destroy(c)
        elif ftype == CONTROL:
            if sub == C_ANSWER and c.kind == "out":
                log("** клиент ответил на входящий, включаю эхо")
                c.mode, c.answered = "echo", True
            elif sub == C_RINGING:
                log("** у клиента звонит")
        elif ftype == VOICE:
            c.fmt = sub
            self.on_voice(c, payload)
        elif ftype == DTMF:
            log(f"** DTMF '{chr(sub)}' от клиента")

    def route(self, c):
        ext = c.ext
        log(f"** {c.user} звонит на {ext}")
        if ext in ("600", "700", "800"):
            self.send_full(c, IAX, ACCEPT, ie32(IE_FORMAT, c.fmt))
            self.send_full(c, CONTROL, C_RINGING)
            if ext == "800":
                c.timers.append((time.monotonic() + 1.0, lambda: self.callback(c)))
            else:
                c.timers.append((time.monotonic() + 1.0, lambda: self.answer(c, "echo" if ext == "600" else "tone")))
        elif ext == "486":
            self.send_full(c, IAX, ACCEPT, ie32(IE_FORMAT, c.fmt))
            self.send_full(c, CONTROL, C_BUSY)
        else:
            self.send_full(c, IAX, REJECT, ie(IE_CAUSE, "No such extension") + ie(IE_CAUSECODE, b"\x01"))
            self.close(c)

    def answer(self, c, mode):
        if c.callno not in self.calls or c.closing:
            return
        c.mode, c.answered = mode, True
        self.send_full(c, CONTROL, C_ANSWER)

    def callback(self, c):
        if c.callno in self.calls and not c.closing:
            self.hangup(c, "Calling you back")
        user = c.user
        t = time.monotonic() + 2.0
        self.timer_calls.append((t, lambda: self.call_user(user)))

    def hangup(self, c, cause="Normal Clearing"):
        self.send_full(c, IAX, HANGUP, ie(IE_CAUSE, cause) + ie(IE_CAUSECODE, b"\x10"))
        self.close(c)

    def call_user(self, user):
        addr = self.regs.get(user)
        if not addr:
            log(f"** {user} не зарегистрирован")
            return
        c = Call(self, addr, "out")
        self.calls[c.callno] = c
        c.user = user
        log(f"** звоню {user} на {addr}")
        self.send_full(c, IAX, NEW, ie16(IE_VERSION, 2) + ie(IE_CALLED_NUMBER, user) + ie(IE_CALLING_NUMBER, "800")
                       + ie(IE_CALLING_NAME, "Test server") + ie32(IE_CAPABILITY, ULAW | ALAW) + ie32(IE_FORMAT, ULAW)
                       + ie(IE_CALLTOKEN, b""))

    def on_voice(self, c, payload):
        c.rx_voice += 1
        if c.rx_voice == 1:
            log(f"** пошёл голос от клиента в вызове #{c.callno} ({len(payload)} байт/кадр)")
        if c.mode == "echo" and c.answered:
            self.send_voice(c, payload)

    def send_voice(self, c, payload):
        ts = int((time.monotonic() - c.start) * 1000) // 20 * 20
        if ts <= c.voice_ts:
            ts = c.voice_ts + 20
        full = not c.voice_started or (ts >> 16) != (c.voice_ts >> 16)
        c.voice_ts = ts
        if full:
            c.voice_started = True
            self.send_full(c, VOICE, c.fmt, payload, ts=ts)
        else:
            self.raw_send(struct.pack(">HH", c.callno, ts & 0xffff) + payload, c.addr)

    # -------------------------------------------------------------- timers
    def tick(self):
        now = time.monotonic()
        for t, fn in [x for x in self.timer_calls if x[0] <= now]:
            self.timer_calls.remove((t, fn))
            fn()
        for c in list(self.calls.values()):
            for t, fn in [x for x in c.timers if x[0] <= now]:
                c.timers.remove((t, fn))
                fn()
            if c.closing and (not c.pending or now > c.close_at):
                self.destroy(c)
                continue
            for p in c.pending:
                if now - p[2] > 0.5 * (2 ** min(p[3], 2)):
                    if p[3] >= 6:
                        log(f"** вызов #{c.callno}: нет ответа, удаляю")
                        self.destroy(c)
                        break
                    p[3] += 1
                    p[2] = now
                    data = bytearray(p[1])
                    data[2] |= 0x80
                    self.raw_send(bytes(data), c.addr)
            if c.answered and c.mode == "tone" and c.callno in self.calls:
                while (c.voice_ts + 20) <= (now - c.start) * 1000:
                    samples = []
                    for _ in range(160):
                        samples.append(int(8000 * math.sin(c.tone_phase)))
                        c.tone_phase += 2 * math.pi * 440 / 8000
                    enc = ulaw if c.fmt == ULAW else alaw
                    self.send_voice(c, bytes(enc(s) for s in samples))
            if c.answered and now - c.last_ping > 5 and c.callno in self.calls:
                c.last_ping = now
                self.send_full(c, IAX, PING)
        if now - self.last_poke > 15:
            self.last_poke = now
            for user, addr in self.regs.items():
                p = Call(self, addr, "poke")
                self.calls[p.callno] = p
                self.close(p)
                self.raw_send(self.frame(p.callno, 0, 0, 0, 0, IAX, POKE), addr)

    def command(self, line):
        parts = line.split()
        if not parts:
            return
        if parts[0] == "call" and len(parts) > 1:
            self.call_user(parts[1])
        elif parts[0] == "hangup":
            for c in list(self.calls.values()):
                if c.kind in ("in", "out") and not c.closing:
                    self.hangup(c)
        elif parts[0] == "quit":
            raise SystemExit
        else:
            log("команды: call <user> | hangup | quit")

    def run(self):
        self.timer_calls = []
        if sys.stdin and sys.stdin.isatty():
            threading.Thread(target=lambda: [self.cmds.put(l) for l in sys.stdin], daemon=True).start()
        while True:
            r, _, _ = select.select([self.sock], [], [], 0.01)
            if r:
                data, addr = self.sock.recvfrom(4096)
                try:
                    self.on_packet(data, addr)
                except Exception as e:
                    log(f"ошибка обработки пакета: {e!r}")
            while not self.cmds.empty():
                self.command(self.cmds.get())
            self.tick()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=4569)
    ap.add_argument("--user", action="append", default=[], help="логин:пароль, можно несколько раз")
    args = ap.parse_args()
    users = dict(u.split(":", 1) for u in (args.user or ["100:secret"]))
    try:
        Server(args.host, args.port, users).run()
    except (KeyboardInterrupt, SystemExit):
        pass


if __name__ == "__main__":
    main()
