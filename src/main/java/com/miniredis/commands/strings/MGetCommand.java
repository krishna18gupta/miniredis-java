package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

public class MGetCommand implements Command {
    @Override
    public String getName() {
        return "MGET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'mget' command");
        }
        List<RedisValue> values = storage.mget(args);
        List<RedisResponse> responses = new ArrayList<>(values.size());
        for (RedisValue val : values) {
            if (val == null || val.getType() != RedisDataType.STRING) {
                responses.add(RedisResponse.nil());
            } else {
                responses.add(RedisResponse.bulkString(val.getAsString()));
            }
        }
        return RedisResponse.array(responses);
    }
}