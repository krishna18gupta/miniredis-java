package com.miniredis.commands.sets;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class SCardCommand implements Command {
    @Override
    public String getName() {
        return "SCARD";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'scard' command");
        }
        try {
            int card = storage.scard(args.get(0));
            return RedisResponse.integer(card);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'scard' command");
        }
        try {
            int card = storage.scard(args.get(0));
            return RedisResponse.integer(card);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}