local Account = {}
Account.__index = Account

function Account.new(initial)
    return setmetatable({ amount = initial, log = function() return "entry" end }, Account)
end

function Account:deposit(n) self.amount = self.amount + n end
function Account:balance() return self.amount end
function Account:describe() return "total=" .. self:balance() .. " " .. self.log() end
function Account.__tostring(a) return "Account(" .. a.amount .. ")" end

return Account
