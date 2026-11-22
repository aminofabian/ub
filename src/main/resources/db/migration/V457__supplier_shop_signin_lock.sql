-- The shared sign-in (portal details on the shop, shop password on the portal) keeps its own
-- failure count and lock, apart from each door's own, so guessing here cannot lock either one.
ALTER TABLE supplier_users
    ADD shop_signin_failures INT NOT NULL DEFAULT 0,
    ADD shop_signin_locked_until TIMESTAMP NULL;
