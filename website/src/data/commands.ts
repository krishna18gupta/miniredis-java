export interface RedisCommand {
  name: string;
  category: 'Strings' | 'Lists' | 'Sets' | 'Hashes' | 'Expiration' | 'Transactions' | 'Keys & Server' | 'System & Protocol';
  syntax: string;
  summary: string;
  example: string;
  complexity: string;
  mutating: boolean;
}

export const COMMAND_CATEGORIES = [
  'All',
  'Strings',
  'Lists',
  'Sets',
  'Hashes',
  'Expiration',
  'Transactions',
  'Keys & Server',
  'System & Protocol'
] as const;

export const COMMANDS: RedisCommand[] = [
  // --- Strings & Numbers (10) ---
  {
    name: 'SET',
    category: 'Strings',
    syntax: 'SET key value',
    summary: 'Set the string value of a key, clearing any existing TTL.',
    example: 'SET user:100 "Krishna"',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'GET',
    category: 'Strings',
    syntax: 'GET key',
    summary: 'Get the string value of a key, or nil if the key does not exist.',
    example: 'GET user:100',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'MSET',
    category: 'Strings',
    syntax: 'MSET key value [key value ...]',
    summary: 'Set multiple keys to multiple values atomically in canonical lock order.',
    example: 'MSET k1 "v1" k2 "v2" k3 "v3"',
    complexity: 'O(N) where N is number of keys',
    mutating: true
  },
  {
    name: 'MGET',
    category: 'Strings',
    syntax: 'MGET key [key ...]',
    summary: 'Get the values of all specified keys. Deep copies under lock prevent torn reads.',
    example: 'MGET k1 k2 k3 missing',
    complexity: 'O(N) where N is number of keys',
    mutating: false
  },
  {
    name: 'INCR',
    category: 'Strings',
    syntax: 'INCR key',
    summary: 'Increment the integer value of a key by one with 64-bit overflow validation.',
    example: 'INCR page:views',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'DECR',
    category: 'Strings',
    syntax: 'DECR key',
    summary: 'Decrement the integer value of a key by one with underflow validation.',
    example: 'DECR stock:count',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'INCRBY',
    category: 'Strings',
    syntax: 'INCRBY key increment',
    summary: 'Increment the integer value of a key by the given integer value.',
    example: 'INCRBY score 25',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'DECRBY',
    category: 'Strings',
    syntax: 'DECRBY key decrement',
    summary: 'Decrement the integer value of a key by the given integer value.',
    example: 'DECRBY balance 15',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'APPEND',
    category: 'Strings',
    syntax: 'APPEND key value',
    summary: 'Append a string to the existing value of a key, returning new length.',
    example: 'APPEND greeting " World"',
    complexity: 'O(1) amortized',
    mutating: true
  },
  {
    name: 'STRLEN',
    category: 'Strings',
    syntax: 'STRLEN key',
    summary: 'Get the length of the value stored in a key in UTF-8 bytes.',
    example: 'STRLEN greeting',
    complexity: 'O(1)',
    mutating: false
  },

  // --- Keys & Database (8) ---
  {
    name: 'DEL',
    category: 'Keys & Server',
    syntax: 'DEL key [key ...]',
    summary: 'Delete one or more keys and return the count of removed keys.',
    example: 'DEL temp:1 temp:2',
    complexity: 'O(N) where N is number of keys',
    mutating: true
  },
  {
    name: 'EXISTS',
    category: 'Keys & Server',
    syntax: 'EXISTS key [key ...]',
    summary: 'Determine if a key or keys exist in the database.',
    example: 'EXISTS user:100 session:abc',
    complexity: 'O(N)',
    mutating: false
  },
  {
    name: 'KEYS',
    category: 'Keys & Server',
    syntax: 'KEYS pattern',
    summary: 'Find all keys matching the given glob pattern (*, ?, [a-z]).',
    example: 'KEYS user:*',
    complexity: 'O(N) where N is database size',
    mutating: false
  },
  {
    name: 'DBSIZE',
    category: 'Keys & Server',
    syntax: 'DBSIZE',
    summary: 'Return the total number of keys currently stored in the database.',
    example: 'DBSIZE',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'FLUSHDB',
    category: 'Keys & Server',
    syntax: 'FLUSHDB',
    summary: 'Delete all keys from the current database.',
    example: 'FLUSHDB',
    complexity: 'O(N)',
    mutating: true
  },
  {
    name: 'RANDOMKEY',
    category: 'Keys & Server',
    syntax: 'RANDOMKEY',
    summary: 'Return a random key name from the database.',
    example: 'RANDOMKEY',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'TYPE',
    category: 'Keys & Server',
    syntax: 'TYPE key',
    summary: 'Determine the type of value stored at key (string, list, set, hash, none).',
    example: 'TYPE user:100',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'RENAME',
    category: 'Keys & Server',
    syntax: 'RENAME key newkey',
    summary: 'Rename a key, preserving value and remaining TTL.',
    example: 'RENAME old_name new_name',
    complexity: 'O(1)',
    mutating: true
  },

  // --- Lists (6) ---
  {
    name: 'LPUSH',
    category: 'Lists',
    syntax: 'LPUSH key element [element ...]',
    summary: 'Prepend one or multiple elements to the head of a list.',
    example: 'LPUSH queue "job1" "job2"',
    complexity: 'O(1) for each element',
    mutating: true
  },
  {
    name: 'RPUSH',
    category: 'Lists',
    syntax: 'RPUSH key element [element ...]',
    summary: 'Append one or multiple elements to the tail of a list.',
    example: 'RPUSH queue "job3"',
    complexity: 'O(1) for each element',
    mutating: true
  },
  {
    name: 'LPOP',
    category: 'Lists',
    syntax: 'LPOP key',
    summary: 'Remove and get the first element in a list. Auto-cleans empty lists.',
    example: 'LPOP queue',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'RPOP',
    category: 'Lists',
    syntax: 'RPOP key',
    summary: 'Remove and get the last element in a list. Auto-cleans empty lists.',
    example: 'RPOP queue',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'LLEN',
    category: 'Lists',
    syntax: 'LLEN key',
    summary: 'Get the length of a list.',
    example: 'LLEN queue',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'LRANGE',
    category: 'Lists',
    syntax: 'LRANGE key start stop',
    summary: 'Get a range of elements from a list. Supports negative offsets (0 -1).',
    example: 'LRANGE queue 0 -1',
    complexity: 'O(S+N) where S is start offset, N is range length',
    mutating: false
  },

  // --- Sets (5) ---
  {
    name: 'SADD',
    category: 'Sets',
    syntax: 'SADD key member [member ...]',
    summary: 'Add one or more members to a set, ignoring existing members.',
    example: 'SADD tags "database" "java" "redis"',
    complexity: 'O(1) for each member',
    mutating: true
  },
  {
    name: 'SREM',
    category: 'Sets',
    syntax: 'SREM key member [member ...]',
    summary: 'Remove one or more members from a set. Auto-cleans empty sets.',
    example: 'SREM tags "database"',
    complexity: 'O(1) for each member',
    mutating: true
  },
  {
    name: 'SISMEMBER',
    category: 'Sets',
    syntax: 'SISMEMBER key member',
    summary: 'Determine if a given value is a member of a set.',
    example: 'SISMEMBER tags "java"',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'SMEMBERS',
    category: 'Sets',
    syntax: 'SMEMBERS key',
    summary: 'Get all the members in a set. Returns a defensive snapshot.',
    example: 'SMEMBERS tags',
    complexity: 'O(N) where N is set cardinality',
    mutating: false
  },
  {
    name: 'SCARD',
    category: 'Sets',
    syntax: 'SCARD key',
    summary: 'Get the number of members in a set.',
    example: 'SCARD tags',
    complexity: 'O(1)',
    mutating: false
  },

  // --- Hashes (5) ---
  {
    name: 'HSET',
    category: 'Hashes',
    syntax: 'HSET key field value [field value ...]',
    summary: 'Set the string value of one or more hash fields.',
    example: 'HSET user:1 name "Alice" role "Admin"',
    complexity: 'O(1) per field',
    mutating: true
  },
  {
    name: 'HGET',
    category: 'Hashes',
    syntax: 'HGET key field',
    summary: 'Get the value of a hash field.',
    example: 'HGET user:1 name',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'HGETALL',
    category: 'Hashes',
    syntax: 'HGETALL key',
    summary: 'Get all the fields and values in a hash as an interleaved array.',
    example: 'HGETALL user:1',
    complexity: 'O(N) where N is hash size',
    mutating: false
  },
  {
    name: 'HDEL',
    category: 'Hashes',
    syntax: 'HDEL key field [field ...]',
    summary: 'Delete one or more hash fields. Auto-cleans empty hashes.',
    example: 'HDEL user:1 role',
    complexity: 'O(1) per field',
    mutating: true
  },
  {
    name: 'HEXISTS',
    category: 'Hashes',
    syntax: 'HEXISTS key field',
    summary: 'Determine if a hash field exists.',
    example: 'HEXISTS user:1 name',
    complexity: 'O(1)',
    mutating: false
  },

  // --- Expiration & TTL (7) ---
  {
    name: 'EXPIRE',
    category: 'Expiration',
    syntax: 'EXPIRE key seconds',
    summary: 'Set a key timeout in seconds. Non-positive values immediately delete the key.',
    example: 'EXPIRE session:abc 3600',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'PEXPIRE',
    category: 'Expiration',
    syntax: 'PEXPIRE key milliseconds',
    summary: 'Set a key timeout in milliseconds.',
    example: 'PEXPIRE token:xyz 5000',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'PEXPIREAT',
    category: 'Expiration',
    syntax: 'PEXPIREAT key unix-time-milliseconds',
    summary: 'Set the expiration for a key as an absolute UNIX timestamp in milliseconds.',
    example: 'PEXPIREAT token:xyz 1893456000000',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'TTL',
    category: 'Expiration',
    syntax: 'TTL key',
    summary: 'Get time to live for a key in seconds (-1 if persistent, -2 if missing).',
    example: 'TTL session:abc',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'PTTL',
    category: 'Expiration',
    syntax: 'PTTL key',
    summary: 'Get time to live for a key in milliseconds (-1 if persistent, -2 if missing).',
    example: 'PTTL token:xyz',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'PERSIST',
    category: 'Expiration',
    syntax: 'PERSIST key',
    summary: 'Remove the existing expiration from a key, turning it into a persistent key.',
    example: 'PERSIST session:abc',
    complexity: 'O(1)',
    mutating: true
  },
  {
    name: 'SETEX',
    category: 'Expiration',
    syntax: 'SETEX key seconds value',
    summary: 'Set the string value of a key and set its expiration in seconds atomically.',
    example: 'SETEX auth_code 300 "991204"',
    complexity: 'O(1)',
    mutating: true
  },

  // --- Transactions (5) ---
  {
    name: 'WATCH',
    category: 'Transactions',
    syntax: 'WATCH key [key ...]',
    summary: 'Watch the given keys to determine execution of the MULTI/EXEC block (CAS).',
    example: 'WATCH acc:alice acc:bob',
    complexity: 'O(1) per key',
    mutating: false
  },
  {
    name: 'UNWATCH',
    category: 'Transactions',
    syntax: 'UNWATCH',
    summary: 'Forget about all watched keys for this client connection.',
    example: 'UNWATCH',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'MULTI',
    category: 'Transactions',
    syntax: 'MULTI',
    summary: 'Mark the start of a transaction block. Subsequent commands are queued.',
    example: 'MULTI',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'EXEC',
    category: 'Transactions',
    syntax: 'EXEC',
    summary: 'Execute all queued commands atomically using 256-stripe canonical locking.',
    example: 'EXEC',
    complexity: 'Depends on commands queued',
    mutating: true
  },
  {
    name: 'DISCARD',
    category: 'Transactions',
    syntax: 'DISCARD',
    summary: 'Discard all commands issued after MULTI.',
    example: 'DISCARD',
    complexity: 'O(1)',
    mutating: false
  },

  // --- System, Telemetry & Persistence (11) ---
  {
    name: 'PING',
    category: 'System & Protocol',
    syntax: 'PING [message]',
    summary: 'Ping the server. Returns PONG or echoes back the provided message.',
    example: 'PING "hello"',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'ECHO',
    category: 'System & Protocol',
    syntax: 'ECHO message',
    summary: 'Echo the given string back to client.',
    example: 'ECHO "MiniRedis rocks"',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'SAVE',
    category: 'System & Protocol',
    syntax: 'SAVE',
    summary: 'Synchronously flush pending write-ahead AOF buffers to disk.',
    example: 'SAVE',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'SHUTDOWN',
    category: 'System & Protocol',
    syntax: 'SHUTDOWN [NOSAVE|SAVE]',
    summary: 'Synchronously save the dataset if requested, close clients, and terminate.',
    example: 'SHUTDOWN SAVE',
    complexity: 'O(N)',
    mutating: false
  },
  {
    name: 'BGREWRITEAOF',
    category: 'System & Protocol',
    syntax: 'BGREWRITEAOF',
    summary: 'Asynchronously compact the Append-Only File while capturing live incoming diffs.',
    example: 'BGREWRITEAOF',
    complexity: 'O(N) in background worker',
    mutating: false
  },
  {
    name: 'HELLO',
    category: 'System & Protocol',
    syntax: 'HELLO [protover]',
    summary: 'Handshake command returning server metadata and protocol compatibility.',
    example: 'HELLO 2',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'SELECT',
    category: 'System & Protocol',
    syntax: 'SELECT index',
    summary: 'Select the Redis logical database (0 is supported).',
    example: 'SELECT 0',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'CLIENT',
    category: 'System & Protocol',
    syntax: 'CLIENT SETNAME name | CLIENT LIST | CLIENT SETINFO ...',
    summary: 'Client connection inspection and identification management.',
    example: 'CLIENT LIST',
    complexity: 'O(N) for LIST',
    mutating: false
  },
  {
    name: 'AUTH',
    category: 'System & Protocol',
    syntax: 'AUTH [username] password',
    summary: 'Authenticate connection for compatibility with external Redis drivers.',
    example: 'AUTH default secret',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'INFO',
    category: 'System & Protocol',
    syntax: 'INFO [server|clients|memory|persistence|stats|keyspace]',
    summary: 'Get information and runtime telemetry statistics about the server.',
    example: 'INFO stats',
    complexity: 'O(1)',
    mutating: false
  },
  {
    name: 'COMMAND',
    category: 'System & Protocol',
    syntax: 'COMMAND [DOCS|COUNT]',
    summary: 'Command catalog provider ensuring seamless connection with redis-cli.',
    example: 'COMMAND COUNT',
    complexity: 'O(1)',
    mutating: false
  }
];
