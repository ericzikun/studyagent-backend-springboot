USE studyagent;

-- Only unpaid add-on attempts may inherit the current catalog validity. Paid
-- orders keep their original evidence boundary and are refunded by fulfillment
-- if their immutable snapshot is incomplete.
UPDATE recharge_orders ro
JOIN addon_package_defs apd
  ON apd.addon_code = ro.addon_code
SET ro.validity_months_snapshot = apd.validity_months,
    ro.updated_at = NOW()
WHERE ro.order_type = 'addon'
  AND ro.status IN ('pending', 'pending_checkout', 'checkout_created')
  AND ro.validity_months_snapshot IS NULL
  AND apd.validity_months > 0;
