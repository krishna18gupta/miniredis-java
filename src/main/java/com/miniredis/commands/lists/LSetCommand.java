package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;

public class LSetCommand implements Command {

    @Override
    public String getName() {
        return "LSET";
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
        if (args.size() != 3) {
            return RedisResponse.error("ERR wrong number of arguments for 'lset' command");
        }

        ByteSequence key = args.get(0);
        long index;
        try {
            index = Long.parseLong(args.get(1).toUtf8String());
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
        ByteSequence element = args.get(2);

        try {
            StorageEngine.LSetStatus status = storage.lsetBytes(key, index, element);
            switch (status) {
                case OK:
                    return RedisResponse.ok();
                case NO_SUCH_KEY:
                    return RedisResponse.error("ERR no such key");
                case INDEX_OUT_OF_RANGE:
                    return RedisResponse.error("ERR index out of range");
                default:
                    return RedisResponse.error("ERR unexpected status");
            }
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
