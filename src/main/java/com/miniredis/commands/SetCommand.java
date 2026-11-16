package com.miniredis.commands;

import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SetCommand implements Command {

    @Override
    public String getName() {
        return "SET";
    }

    public static RedisResponse validateSyntaxBytes(List<ByteSequence> args) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'set' command");
        }
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        boolean hasExpire = false;

        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).toUtf8String().toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX":
                    if (nx || xx) return RedisResponse.error("ERR syntax error");
                    nx = true;
                    break;
                case "XX":
                    if (nx || xx) return RedisResponse.error("ERR syntax error");
                    xx = true;
                    break;
                case "GET":
                    if (get) return RedisResponse.error("ERR syntax error");
                    get = true;
                    break;
                case "KEEPTTL":
                    if (keepTtl || hasExpire) return RedisResponse.error("ERR syntax error");
                    keepTtl = true;
                    break;
                case "EX":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long sec = Long.parseLong(args.get(i).toUtf8String());
                        if (sec <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                        if (sec > Long.MAX_VALUE / 1000L) return RedisResponse.error("ERR invalid expire time in 'set' command");
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    hasExpire = true;
                    break;
                case "PX":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long ms = Long.parseLong(args.get(i).toUtf8String());
                        if (ms <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    hasExpire = true;
                    break;
                case "EXAT":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long sec = Long.parseLong(args.get(i).toUtf8String());
                        if (sec > Long.MAX_VALUE / 1000L || sec < Long.MIN_VALUE / 1000L) {
                            return RedisResponse.error("ERR invalid expire time in 'set' command");
                        }
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    hasExpire = true;
                    break;
                case "PXAT":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        Long.parseLong(args.get(i).toUtf8String());
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    hasExpire = true;
                    break;
                default:
                    return RedisResponse.error("ERR syntax error");
            }
        }
        return null;
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        List<ByteSequence> byteArgs = new ArrayList<>(args.size());
        for (String arg : args) {
            byteArgs.add(ByteSequence.ofUtf8(arg));
        }
        return executeBytes(byteArgs, storage);
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'set' command");
        }
        ByteSequence key = args.get(0);
        ByteSequence value = args.get(1);

        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        boolean hasExpire = false;
        Long expireAt = null;

        long now = storage.getClock().millis();

        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).toUtf8String().toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX":
                    if (nx || xx) return RedisResponse.error("ERR syntax error");
                    nx = true;
                    break;
                case "XX":
                    if (nx || xx) return RedisResponse.error("ERR syntax error");
                    xx = true;
                    break;
                case "GET":
                    if (get) return RedisResponse.error("ERR syntax error");
                    get = true;
                    break;
                case "KEEPTTL":
                    if (keepTtl || hasExpire) return RedisResponse.error("ERR syntax error");
                    keepTtl = true;
                    break;
                case "EX":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long sec = Long.parseLong(args.get(i).toUtf8String());
                        if (sec <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                        if (sec > (Long.MAX_VALUE - now) / 1000L) {
                            return RedisResponse.error("ERR invalid expire time in 'set' command");
                        }
                        expireAt = now + sec * 1000L;
                        hasExpire = true;
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    break;
                case "PX":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long ms = Long.parseLong(args.get(i).toUtf8String());
                        if (ms <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                        if (ms > Long.MAX_VALUE - now) {
                            return RedisResponse.error("ERR invalid expire time in 'set' command");
                        }
                        expireAt = now + ms;
                        hasExpire = true;
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    break;
                case "EXAT":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long sec = Long.parseLong(args.get(i).toUtf8String());
                        if (sec > Long.MAX_VALUE / 1000L || sec < Long.MIN_VALUE / 1000L) {
                            return RedisResponse.error("ERR invalid expire time in 'set' command");
                        }
                        expireAt = sec * 1000L;
                        hasExpire = true;
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    break;
                case "PXAT":
                    if (hasExpire || keepTtl) return RedisResponse.error("ERR syntax error");
                    if (i + 1 >= args.size()) return RedisResponse.error("ERR syntax error");
                    i++;
                    try {
                        long epochMs = Long.parseLong(args.get(i).toUtf8String());
                        expireAt = epochMs;
                        hasExpire = true;
                    } catch (NumberFormatException e) {
                        return RedisResponse.error("ERR value is not an integer or out of range");
                    }
                    break;
                default:
                    return RedisResponse.error("ERR syntax error");
            }
        }

        try {
            return storage.setExtended(key, value, new StorageEngine.SetOptions(nx, xx, get, keepTtl, expireAt, hasExpire));
        } catch (WrongTypeException e) {
            return RedisResponse.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }
}
