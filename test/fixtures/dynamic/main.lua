local M = {}
function M.attack() return "attack!" end
function M.defend() return "defend!" end
function M.reveal() return "hidden" end

-- static key strings pin attack and defend; reveal has no key string and is renamed
print(M["attack"](), M["defend"]())
print(M.reveal())

-- a global reached through _G
function shared() return "shared" end
print(_G["shared"]())