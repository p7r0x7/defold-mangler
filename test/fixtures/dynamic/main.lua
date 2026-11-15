local M = {}
function M.attack() return "attack!" end
function M.defend() return "defend!" end
function M.reveal() return "hidden" end

-- attack and defend are looked up by string, so they keep their names; the third is free to go
for _, action in ipairs({ "attack", "defend" }) do print(M[action]()) end
print(M.reveal())

-- a global reached through _G
function shared() return "shared" end
print(_G["shared"]())
