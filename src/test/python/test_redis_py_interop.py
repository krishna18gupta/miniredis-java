#!/usr/bin/env python3
"""
Phase 17 Stage F: redis-py (Python Official Redis Client) Interoperability Verification
Connects to MiniRedis native TCP RESP2 server and runs CRUD operations using redis-py 8.1.0+.
"""

import sys
import time
import subprocess
import redis

def run_tests(host="127.0.0.1", port=6379):
    print("=" * 70)
    print("   MiniRedis Phase 17: Python redis-py Interoperability Verification   ")
    print("=" * 70)
    print(f"Connecting to MiniRedis on {host}:{port} via redis-py {redis.__version__}...")

    # Explicitly configure protocol=2 (RESP2) for clients that default to RESP3
    client = redis.Redis(host=host, port=port, protocol=2, decode_responses=True, socket_timeout=3)

    # 1. PING
    pong = client.ping()
    assert pong is True or pong == "PONG", f"PING failed: {pong}"
    print("  [PASS] PING -> True / PONG")

    # 2. STRINGS: SET, GET, STRLEN, INCR, DEL
    client.set("py:greeting", "Hello from redis-py")
    val = client.get("py:greeting")
    assert val == "Hello from redis-py", f"GET failed: {val}"
    print("  [PASS] SET & GET string roundtrip")

    strlen = client.strlen("py:greeting")
    assert strlen == len("Hello from redis-py"), f"STRLEN failed: {strlen}"
    print("  [PASS] STRLEN calculation")

    client.set("py:counter", "10")
    incr_val = client.incr("py:counter")
    assert incr_val == 11, f"INCR failed: {incr_val}"
    print("  [PASS] INCR atomic arithmetic")

    del_count = client.delete("py:greeting", "py:counter")
    assert del_count == 2, f"DELETE failed: {del_count}"
    print("  [PASS] DEL multiple keys")

    # 2b. STRINGS: SET with options (NX, XX, GET, EX, KEEPTTL)
    res_nx1 = client.set("py:opt", "val1", nx=True)
    assert res_nx1 is True, f"SET NX failed on new key: {res_nx1}"
    res_nx2 = client.set("py:opt", "val2", nx=True)
    assert res_nx2 is None, f"SET NX expected None on existing key: {res_nx2}"
    res_xx = client.set("py:opt", "val3", xx=True)
    assert res_xx is True, f"SET XX failed on existing key: {res_xx}"
    res_get = client.set("py:opt", "val4", get=True, ex=300)
    assert res_get == "val3", f"SET GET expected val3, got: {res_get}"
    opt_ttl = client.ttl("py:opt")
    assert 0 < opt_ttl <= 300, f"TTL failed: {opt_ttl}"
    res_kt = client.set("py:opt", "val5", keepttl=True)
    assert res_kt is True, f"SET KEEPTTL failed: {res_kt}"
    opt_ttl2 = client.ttl("py:opt")
    assert 0 < opt_ttl2 <= 300, f"KEEPTTL failed: {opt_ttl2}"
    client.delete("py:opt")
    print("  [PASS] SET options: NX, XX, GET, EX, KEEPTTL")

    # 3. LISTS: RPUSH, LRANGE, LLEN, LPOP
    client.rpush("py:jobs", "job1", "job2", "job3")
    assert client.llen("py:jobs") == 3
    items = client.lrange("py:jobs", 0, -1)
    assert items == ["job1", "job2", "job3"], f"LRANGE failed: {items}"
    popped = client.lpop("py:jobs")
    assert popped == "job1", f"LPOP failed: {popped}"
    client.delete("py:jobs")
    print("  [PASS] Lists: RPUSH, LRANGE, LLEN, LPOP")

    # 3b. LISTS: LINDEX, LSET, LINSERT, LPOP/RPOP with count
    client.rpush("py:tasks", "task1", "task2", "task3", "task4")
    assert client.lindex("py:tasks", 0) == "task1"
    assert client.lindex("py:tasks", -1) == "task4"
    assert client.lset("py:tasks", 1, "task2_mod") is True
    assert client.lindex("py:tasks", 1) == "task2_mod"
    assert client.linsert("py:tasks", "BEFORE", "task2_mod", "task1_5") == 5
    popped_l = client.lpop("py:tasks", 2)
    assert popped_l == ["task1", "task1_5"], f"LPOP count failed: {popped_l}"
    popped_r = client.rpop("py:tasks", 2)
    assert popped_r == ["task4", "task3"], f"RPOP count failed: {popped_r}"
    assert client.lpop("py:tasks") == "task2_mod"
    assert client.lpop("py:tasks") is None
    client.delete("py:tasks")
    print("  [PASS] Lists extended: LINDEX, LSET, LINSERT, LPOP/RPOP count")

    # 4. SETS: SADD, SISMEMBER, SMEMBERS, SCARD, SREM
    client.sadd("py:frameworks", "fastapi", "django", "flask")
    assert client.scard("py:frameworks") == 3
    assert client.sismember("py:frameworks", "django") == 1
    assert client.sismember("py:frameworks", "express") == 0
    members = client.smembers("py:frameworks")
    assert members == {"fastapi", "django", "flask"}, f"SMEMBERS failed: {members}"
    client.srem("py:frameworks", "flask")
    assert client.scard("py:frameworks") == 2
    client.delete("py:frameworks")
    print("  [PASS] Sets: SADD, SISMEMBER, SMEMBERS, SCARD, SREM")

    # 5. HASHES: HSET, HGET, HGETALL, HEXISTS, HDEL
    client.hset("py:user:42", mapping={"name": "Alice", "role": "admin"})
    assert client.hget("py:user:42", "name") == "Alice"
    assert client.hexists("py:user:42", "role") == 1
    hmap = client.hgetall("py:user:42")
    assert hmap == {"name": "Alice", "role": "admin"}, f"HGETALL failed: {hmap}"
    client.hdel("py:user:42", "role")
    assert client.hexists("py:user:42", "role") == 0
    client.delete("py:user:42")
    print("  [PASS] Hashes: HSET, HGET, HGETALL, HEXISTS, HDEL")

    # 6. EXPIRATION: EXPIRE, TTL, PERSIST
    client.set("py:temp", "ephemeral")
    client.expire("py:temp", 300)
    ttl = client.ttl("py:temp")
    assert 0 < ttl <= 300, f"TTL failed: {ttl}"
    client.persist("py:temp")
    assert client.ttl("py:temp") == -1, "PERSIST failed"
    client.delete("py:temp")
    print("  [PASS] Expiration: EXPIRE, TTL, PERSIST")

    # 7. ERROR HANDLING: WRONGTYPE
    client.set("py:strkey", "scalar")
    try:
        client.lpush("py:strkey", "bad_item")
        assert False, "Should have thrown ResponseError WRONGTYPE"
    except redis.exceptions.ResponseError as e:
        assert "WRONGTYPE" in str(e), f"Expected WRONGTYPE, got: {e}"
        print(f"  [PASS] Error Semantics: caught expected WRONGTYPE exception ({e})")
    client.delete("py:strkey")

    # 8. BINARY SAFETY: raw bytes with nulls, CRLF, and non-UTF-8 bytes
    bin_client = redis.Redis(host=host, port=port, protocol=2, decode_responses=False, socket_timeout=3)
    raw_payload = b"\x00\x01\x02\xff\xfe\r\n\x00Hello\x00World\x80"
    raw_key = b"py:bin:\x00\xff"
    bin_client.set(raw_key, raw_payload)
    fetched = bin_client.get(raw_key)
    assert fetched == raw_payload, f"Binary mismatch! Expected {raw_payload!r}, got {fetched!r}"
    bin_client.delete(raw_key)
    print("  [PASS] Binary Safety: raw bytes with nulls, CRLF and non-UTF8 bytes roundtrip via redis-py")

    print("-" * 70)
    print("   ALL PYTHON redis-py INTEROPERABILITY TESTS PASSED SUCCESSFULLY!   ")
    print("=" * 70)

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6379
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    run_tests(host, port)
