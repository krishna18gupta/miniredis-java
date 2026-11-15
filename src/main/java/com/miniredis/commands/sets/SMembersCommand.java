package com.miniredis.commands.sets;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;
import java.util.Set;

public class SMembersCommand implements Command {
    @Override
    public String getName() {
        return "SMEMBERS";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'smembers' command");
        }
        try {
            Set<String> members = storage.smembers(args.get(0));
            return RedisResponse.stringList(members);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'smembers' command");
        }
        try {
            Set<ByteSequence> members = storage.smembersBytes(args.get(0));
            return RedisResponse.byteList(members);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}