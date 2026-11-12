"""Validate the exact parsing regexes used by Api.java / MainActivity.java
against real responses captured from the bot. Python's re follows the same
semantics for these constructs, so this is a faithful check of the parsing layer.
"""
import re

STATUS = (
    "ZenithProxy 3.7.3+java.1.21.4 - YourName\n"
    "Status: Online\n"
    "Uptime: 51s\n"
    "Online Duration: 00:00:45\n"
    "Health: 20.0\n"
    "Dimension: overworld\n"
    "Ping: 46ms\n"
    "Proxy IP: 203.0.113.10:25565\n"
    "Server: mc.example.com:25565\n"
    "Connected Player: None\n"
    "2b2t Queue: Priority: 1 [00:03:19]\n"
    "Regular: 636 [37:17:00]\n"
    "Coordinates: ||[1120244, 70, 11882371]||\n"
    "AutoUpdate: off"
)

DEVICE_MSG = "Login Here: https://www.microsoft.com/link?otc=GXZRUZVQ with code: GXZRUZVQ"

P_COORDS = re.compile(
    r"Coordinates:\s*\|*\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\]"
)
P_LINK = re.compile(r"(https?://\S*?microsoft\.com/link\S*)")
P_CODE = re.compile(r"code[:\s]+([A-Z0-9]{6,12})")
P_DEATH = re.compile(
    r"(drowned|died|was slain|was killed|was shot|blew up|fell from|hit the ground|"
    r"burned|went up in flames|starved|suffocated|withered|was fireballed|"
    r"was pummeled|was pricked|was impaled|froze|was struck by lightning|"
    r"experienced kinetic energy|tried to swim in lava)",
    re.IGNORECASE,
)


def field(text, key):
    m = re.search(r"(?m)^\s*" + re.escape(key) + r":\s*(.+)$", text)
    return m.group(1).strip() if m else ""


ok = True


def check(name, got, want):
    global ok
    good = got == want
    ok = ok and good
    print(("PASS " if good else "FAIL ") + name + " => " + repr(got) + ("" if good else "  want " + repr(want)))


m = P_COORDS.search(STATUS)
check("coords", m.groups() if m else None, ("1120244", "70", "11882371"))
check("dimension", field(STATUS, "Dimension"), "overworld")
check("online duration", field(STATUS, "Online Duration"), "00:00:45")
check("server", field(STATUS, "Server"), "mc.example.com:25565")
check("health", field(STATUS, "Health"), "20.0")

lm = P_LINK.search(DEVICE_MSG)
url = lm.group(1).rstrip(")],;") if lm else None
check("device url", url, "https://www.microsoft.com/link?otc=GXZRUZVQ")
cm = P_CODE.search(DEVICE_MSG)
check("device code", cm.group(1) if cm else None, "GXZRUZVQ")

check("death drowned", bool(P_DEATH.search("||344813 drowned")), True)
check("death slain", bool(P_DEATH.search("Steve was slain by Zombie")), True)
check("death non-death", bool(P_DEATH.search("Loaded 120 chunks")), False)

# offline case: proxy reports [0,0,0] and no online duration -> app must not fake a position
OFFLINE = "Status: Offline\nDimension: None\nCoordinates: ||[0, 0, 0]||"
m2 = P_COORDS.search(OFFLINE)
check("offline coords parsed", m2.groups() if m2 else None, ("0", "0", "0"))
check("offline online-duration empty", field(OFFLINE, "Online Duration"), "")

print("\nALL PARSING CHECKS PASSED" if ok else "\nSOME CHECKS FAILED")
