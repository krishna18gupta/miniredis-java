package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LInsertCommand implements Command {

    @Override
    public String getName() {
        return "LINSERT";
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
        if (args.size() != 4) {
            return RedisResponse.error("ERR wrong number of arguments for 'linsert' command");
        }

        ByteSequence key = args.get(0);
        String position = args.get(1).toUtf8String().toUpperCase(Locale.ROOT);
        boolean before;
        if ("BEFORE".equals(position)) {
            before = true;
        } else if ("AFTER".equals(position)) {
            before = false;
        } else {
            return RedisResponse.error("ERR syntax error");
        }

        ByteSequence pivot = args.get(2);
        ByteSequence element = args.get(3);

        try {
            int result = storage.linsertBytes(key, before, pivot, element);
            if (result == 0) {
                return RedisResponse.integer(0).withMutated(false);
            }
            if (result == -1) {
                return RedisResponse.integer(-1).withMutated(false);
            }
            return RedisResponse.integer(result);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
