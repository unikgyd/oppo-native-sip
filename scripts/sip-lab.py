"""Local, isolated SIP/RTP test peer. No public phone numbers or real SMS are used.

python scripts/sip-lab.py --host 127.0.0.1 --bind 127.0.0.1
Control endpoints bind to loopback only: http://127.0.0.1:8766/{state,message,invite,bye,drop}
This is a test fixture, not a production SIP server.
"""
import argparse
import hashlib
import http.server
import json
import re
import socket
import struct
import threading
import time
import uuid


def parse(raw):
    header, body = raw.split(b"\r\n\r\n", 1)
    lines = header.decode("utf-8", errors="replace").split("\r\n")
    headers = {}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        headers.setdefault(key.lower(), []).append(value.strip())
    return lines[0], headers, body


class Lab:
    def __init__(self, host, port, bind):
        self.host, self.port, self.bind = host, port, bind
        self.signal_host = bind
        self.conn = None
        self.send_lock = threading.Lock()
        self.contact = None
        self.register_count = 0
        self.authenticated = 0
        self.received_messages = []
        self.responses = []
        self.call = None
        self.rtp_rx = 0
        self.rtp_tx = 0
        self.rtp_peer = None
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind((host, 40000))
        threading.Thread(target=self.rtp_receive, daemon=True).start()
        threading.Thread(target=self.rtp_send, daemon=True).start()

    def send(self, start, headers, body=b""):
        if isinstance(body, str):
            body = body.encode("utf-8")
        raw = (start + "\r\n" + "\r\n".join(f"{k}: {v}" for k, v in headers) + f"\r\nContent-Length: {len(body)}\r\n\r\n").encode() + body
        with self.send_lock:
            if not self.conn:
                raise RuntimeError("No registered TCP client")
            self.conn.sendall(raw)

    def reply(self, start, h, code=200, extras=(), body=b""):
        headers = [(k.title(), v) for k in ("via", "from", "to", "call-id", "cseq") for v in h.get(k, [])]
        headers = [(k, v + ";tag=lab-server" if k == "To" and "tag=" not in v else v) for k, v in headers]
        self.send(f"SIP/2.0 {code} { {200:'OK',401:'Unauthorized'}.get(code, 'Response')}", headers + list(extras), body)

    def request(self, method, body=b"", content_type=None):
        cseq = int(time.time()) % 100000
        call_id = uuid.uuid4().hex + "@mysip-lab"
        headers = [("Via", f"SIP/2.0/TCP {self.signal_host}:{self.port};branch=z9hG4bK{uuid.uuid4().hex};rport"),
                   ("Max-Forwards", "70"), ("From", f'"MySIP Lab" <sip:5550100@{self.host}>;tag=lab-tag'),
                   ("To", f"<sip:lab@{self.host}>"), ("Call-ID", call_id), ("CSeq", f"{cseq} {method}"),
                   ("Contact", f"<sip:lab-peer@{self.signal_host}:{self.port};transport=tcp>")]
        if content_type:
            headers.append(("Content-Type", content_type))
        self.send(f"{method} {self.contact} SIP/2.0", headers, body)
        if method == "INVITE":
            self.call = {"id": call_id, "cseq": cseq, "headers": headers, "remote_to": None}
        return call_id

    def process(self, raw):
        start, h, body = parse(raw)
        if start.startswith("SIP/2.0"):
            code = int(start.split()[1])
            self.responses.append({"code": code, "cseq": h.get("cseq", [""])[0]})
            if self.call and h.get("call-id", [None])[0] == self.call["id"] and h["cseq"][0].endswith("INVITE") and code == 200:
                self.call["remote_to"] = h["to"][0]
                match = re.search(rb"c=IN IP4 (\S+).*?m=audio (\d+)", body, re.S)
                if match:
                    self.rtp_peer = (match[1].decode(), int(match[2]))
                headers = [(k, h["to"][0] if k == "To" else (str(self.call["cseq"]) + " ACK" if k == "CSeq" else v)) for k, v in self.call["headers"]]
                self.send(f"ACK {self.contact} SIP/2.0", headers)
            return
        method = start.split()[0]
        if method == "REGISTER":
            self.register_count += 1
            if "authorization" not in h:
                self.reply(start, h, 401, [("WWW-Authenticate", 'Digest realm="mysip-lab", nonce="fixed-test-nonce", algorithm=MD5, qop="auth"')])
                return
            values = {}
            for match in re.finditer(r'(\w+)=(?:"([^"]*)"|([^,\s]+))', h["authorization"][0]):
                values[match[1]] = match[2] if match[2] is not None else match[3]
            md5 = lambda v: hashlib.md5(v.encode()).hexdigest()
            a1 = md5(f"lab:mysip-lab:lab-only-password")
            a2 = md5(f"REGISTER:{values.get('uri', '')}")
            if values.get("qop"):
                expected = md5(f"{a1}:{values.get('nonce')}:{values.get('nc')}:{values.get('cnonce')}:{values.get('qop')}:{a2}")
            else:
                expected = md5(f"{a1}:{values.get('nonce')}:{a2}")
            if values.get("response") != expected:
                self.reply(start, h, 401)
                return
            self.authenticated += 1
            contact = h.get("contact", [""])[0]
            match = re.search(r"<([^>]+)>", contact)
            self.contact = match[1] if match else contact.split(";")[0]
            self.reply(start, h, 200, [("Contact", contact), ("Expires", "180")])
        elif method == "MESSAGE":
            self.received_messages.append({"body": body.decode("utf-8", errors="replace"), "type": h.get("content-type", [""])[0]})
            self.reply(start, h, 200)
        elif method == "INVITE":
            # Exercise outgoing dialing with a PCMU tone source.
            self.call = {"id": h["call-id"][0], "cseq": int(h["cseq"][0].split()[0]), "headers": [], "remote_to": None}
            match = re.search(rb"c=IN IP4 (\S+).*?m=audio (\d+)", body, re.S)
            if match:
                self.rtp_peer = (match[1].decode(), int(match[2]))
            self.reply(start, h, 200, [("Contact", f"<sip:lab-peer@{self.signal_host}:{self.port};transport=tcp>"), ("Content-Type", "application/sdp")], self.sdp())
        elif method in ("BYE", "CANCEL", "OPTIONS", "INFO", "UPDATE"):
            self.reply(start, h, 200)
            if method in ("BYE", "CANCEL"):
                self.rtp_peer = None

    def sdp(self):
        return f"v=0\r\no=lab 1 1 IN IP4 {self.host}\r\ns=MySIP lab\r\nc=IN IP4 {self.host}\r\nt=0 0\r\nm=audio 40000 RTP/AVP 0 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-16\r\na=sendrecv\r\n"

    def bye(self):
        if not self.call or not self.call["remote_to"]:
            raise RuntimeError("No established incoming test call")
        headers = [(k, self.call["remote_to"] if k == "To" else (str(self.call["cseq"] + 1) + " BYE" if k == "CSeq" else v)) for k, v in self.call["headers"]]
        self.send(f"BYE {self.contact} SIP/2.0", headers)
        self.rtp_peer = None

    def rtp_receive(self):
        while True:
            data, peer = self.udp.recvfrom(65535)
            if len(data) >= 12:
                self.rtp_rx += 1
                self.rtp_peer = peer

    def rtp_send(self):
        seq, stamp = 0, 0
        # PCMU silence is deterministic; network statistics prove both media directions.
        while True:
            if self.rtp_peer:
                packet = struct.pack("!BBHII", 0x80, 0, seq % 65536, stamp % (2 ** 32), 0x12345678) + b"\xff" * 160
                self.udp.sendto(packet, self.rtp_peer)
                self.rtp_tx += 1
                seq += 1; stamp += 160
            time.sleep(.02)

    def accept(self):
        server = socket.socket()
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.bind, self.port)); server.listen(4)
        print(f"SIP lab listening on {self.host}:{self.port}; control=127.0.0.1:8766", flush=True)
        while True:
            conn, peer = server.accept()
            self.conn = conn
            try:
                buffer = b""
                while True:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buffer += chunk
                    while True:
                        if buffer.startswith(b"\r\n\r\n"):
                            buffer = buffer[4:]
                            conn.sendall(b"\r\n")
                        elif buffer.startswith(b"\r\n"):
                            buffer = buffer[2:]
                        if b"\r\n\r\n" not in buffer:
                            break
                        header, body = buffer.split(b"\r\n\r\n", 1)
                        m = re.search(rb"(?:^|\r\n)Content-Length:\s*(\d+)", header, re.I)
                        size = int(m[1]) if m else 0
                        if len(body) < size:
                            break
                        raw = header + b"\r\n\r\n" + body[:size]
                        buffer = body[size:]
                        self.process(raw)
            except (OSError, ValueError) as exc:
                print(type(exc).__name__, flush=True)
            finally:
                conn.close()
                if self.conn is conn:
                    self.conn = None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5072)
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args()
    lab = Lab(args.host, args.port, args.bind)

    class Control(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            try:
                if self.path == "/message":
                    lab.request("MESSAGE", "MySIP 锁屏短信测试 / UTF-8 ✓", "text/plain;charset=utf-8")
                elif self.path == "/invite":
                    lab.request("INVITE", lab.sdp(), "application/sdp")
                elif self.path == "/bye":
                    lab.bye()
                elif self.path == "/drop" and lab.conn:
                    lab.conn.shutdown(socket.SHUT_RDWR)
                    lab.conn.close()
                data = {"registrations": lab.register_count, "authenticated": lab.authenticated, "messages": lab.received_messages,
                        "responses": lab.responses[-15:], "rtp_rx": lab.rtp_rx, "rtp_tx": lab.rtp_tx, "rtp_peer": lab.rtp_peer}
                raw = json.dumps(data, ensure_ascii=False).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(raw)
            except Exception as exc:
                self.send_response(409); self.end_headers(); self.wfile.write(str(exc).encode())
        def log_message(self, *args):
            pass

    threading.Thread(target=lab.accept, daemon=True).start()
    http.server.HTTPServer(("127.0.0.1", 8766), Control).serve_forever()


if __name__ == "__main__":
    main()
