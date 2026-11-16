package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LPopCommand implements Command {

    @Override
    public String getName() {
        return "LPOP";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        List<ByteSequence> byteArgs = new ArrayList<>(args.size());
        for (String s : args) {
            byteArgs.add(ByteSequence.ofUtf8(s));
        }
        return executeBytes(byteArgs, storage);
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.isEmpty() || args.size() > 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'lpop' command");
        }

        ByteSequence key = args.get(0);

        if (args.size() == 1) {
            try {
                ByteSequence val = storage.lpop(key);
                return val != null ? RedisResponse.bulkString(val) : RedisResponse.nil().withMutated(false);
            } catch (WrongTypeException e) {
                return RedisResponse.error(e.getMessage());
            }
        }

        // 2-argument form: LPOP key count
        long count;
        try {
            count = Long.parseLong(args.get(1).toUtf8String());
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }

        if (count < 0) {
            return RedisResponse.error("ERR value is out of range, must be positive");
        }

        int clampedCount = (int) Math.min(count, (long) Integer.MAX_VALUE);

        try {
            List<ByteSequence> popped = storage.lpopBytes(key, clampedCount);
            if (popped == null) {
                return RedisResponse.nullArray().withMutated(false);
            }
            if (popped.isEmpty()) {
                return RedisResponse.array(Collections.emptyList()).withMutated(false);
            }
            return RedisResponse.byteList(popped);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
