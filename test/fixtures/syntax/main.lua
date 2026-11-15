-- call forms and chains
local function id(...) return ... end
local obj = { value = 1 }
function obj:get() return self.value end
function obj.make(v) return { value = v, get = obj.get } end

print(id "str", id { 1 }[1], (id(1, 2)), select("#", id(1, 2, 3)))
print(obj:get(), obj.make(5):get(), obj.make(6).get(obj.make(7)))
print(("x"):rep(3), #"abc", -2 ^ 2, not nil, 1 .. 2)
print(0xFF, 1e3, .5, 3 % 2, 7 / 2)

-- nested constructors holding functions
local registry = { a = { run = function() return "ran a" end }, b = { run = function() return "ran b" end } }
print(registry.a.run(), registry.b.run())

-- closures and varargs
local function counter()
    local c = 0
    return function() c = c + 1 return c end
end
local next_id = counter()
print(next_id(), next_id())
local function sum(...) local s = 0 for _, v in ipairs({ ... }) do s = s + v end return s end
print(sum(1, 2, 3))

-- shadowed and re-declared function locals
local function twice(f) return function(x) return f(f(x)) end end
local function inc(x) return x + 1 end
do
    local function inc(x) return x + 10 end
    print(twice(inc)(0))
end
print(twice(inc)(0))
local inc = inc
print(inc(1))
