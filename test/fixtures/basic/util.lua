local M = {}

function M.shout(s) return greet(s):upper() .. "!" end

function M.shout_all(list)
    local out = {}
    for _, v in ipairs(list) do out[#out + 1] = M.shout(v) end
    return table.concat(out, ",")
end

function M.format_money(n) return "$" .. (type(n) == "number" and n or 0) end
function M.format(n) return "fmt" .. n end   -- same name as string.format
function M.floor(x) return "floor " .. x end -- same name as math.floor
function M.sqrt(x) return "sqrt " .. x end   -- same name as math.sqrt

function M.use_alias()
    local m = math
    return m.sqrt(16) + m.floor(2.5)
end

M.helper = function() return "assist" end

return M
