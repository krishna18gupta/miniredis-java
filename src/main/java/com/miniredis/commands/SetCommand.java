package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;
import java.util.Locale;

public class SetCommand implements Command {
    @Override
    public String getName() {
        return "SET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'set' command");
        }
        String key = args.get(0);
        String value = args.get(1);
        Long expireAt = null;

        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).toUpperCase(Locale.ROOT);
            if (opt.equals("EX") && i + 1 < args.size()) {
                try {
                    long sec = Long.parseLong(args.get(++i));
                    if (sec <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                    expireAt = storage.getClock().millis() + sec * 1000L;
                } catch (NumberFormatException e) {
                    return RedisResponse.error("ERR value is not an integer or out of range");
                }
            } else if (opt.equals("PX") && i + 1 < args.size()) {
                try {
                    long ms = Long.parseLong(args.get(++i));
                    if (ms <= 0) return RedisResponse.error("ERR invalid expire time in 'set' command");
                    expireAt = storage.getClock().millis() + ms;
                } catch (NumberFormatException e) {
                    return RedisResponse.error("ERR value is not an integer or out of range");
                }
            } else if (opt.equals("PXAT") && i + 1 < args.size()) {
                try {
                    long epochMs = Long.parseLong(args.get(++i));
                    expireAt = epochMs;
                } catch (NumberFormatException e) {
                    return RedisResponse.error("ERR value is not an integer or out of range");
                }
            } else if (opt.equals("EXAT") && i + 1 < args.size()) {
                try {
                    long epochSec = Long.parseLong(args.get(++i));
                    expireAt = epochSec * 1000L;
                } catch (NumberFormatException e) {
                    return RedisResponse.error("ERR value is not an integer or out of range");
                }
            } else {
                return RedisResponse.error("ERR syntax error");
            }
        }

        storage.set(key, value, expireAt);
        return RedisResponse.ok();
    }
}
