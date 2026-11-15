local util = require("util")
local Account = require("account")

-- a global function that another file calls
function greet(name) return "hello " .. name end

-- a global table holding functions
Game = {}
function Game.start(n) return "started " .. n end
Game.stop = function() return "stopped" end

-- locals bound to functions
local function double(x) return x * 2 end
local triple = function(x) return x * 3 end

-- a local that shadows a standard global which other files still use for real
local function type(x) return "mytype" end

-- function-valued table keys; `update` is an engine callback name and must survive
local handlers = {
    on_done = function() return "done" end,
    update = function() return "update" end,
}

print(greet("world"), double(4), triple(5), type(1))
print(Game.start(1), Game.stop())
print(handlers.on_done(), handlers.update())
print(util.shout("hi"), util.shout_all({ "a", "b" }), util.helper())
print(util.format_money(1234), util.format(7), ("%d items"):format(3), string.format("%s-%s", "a", "b"))
print(util.floor(2.5), math.floor(2.5), util.sqrt(16), util.use_alias())

local acct = Account.new(100)
acct:deposit(50)
print(acct:balance(), acct:describe(), tostring(acct))

_G.dyn_call = function() return "dyn" end
print(_G.dyn_call(), dyn_call())

-- scoping: loop variables, nested functions, repeat-until seeing the body's locals
for i = 1, 3 do
    local function step() return i end
    io.write(step(), " ")
end
local n = 0
repeat
    local function bump() n = n + 1 return n end
    local v = bump()
until v >= 3
print(n)

-- a local named like a function elsewhere, but holding a plain value
local shout = "not a function"
print(shout)

print("greet double shout") -- KEEP: strings and comments are left alone
--[[ greet(double) ]] -- KEEP
