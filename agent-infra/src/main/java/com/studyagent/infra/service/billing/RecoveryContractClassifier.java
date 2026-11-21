package com.studyagent.infra.service.billing;

import com.google.gson.*;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionSchedule;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Pure classification of complete remote snapshots. Missing reads remain UNKNOWN, never NO_SCHEDULE. */
final class RecoveryContractClassifier {
    record Result(String scheduleClass, String cancelMode, String contractHash, String contractJson) {}
    private RecoveryContractClassifier() {}

    static Result classify(Subscription sub, SubscriptionSchedule schedule, boolean complete, String priceId, Long quantity) {
        if (!complete || sub == null || sub.getId() == null || priceId == null || quantity == null || quantity <= 0)
            return unknown();
        String cancelMode = Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()) ? "PERIOD_END" : "RENEW";
        boolean fixedCancellation = sub.getCancelAt() != null && !Boolean.TRUE.equals(sub.getCancelAtPeriodEnd());
        JsonObject contract = new JsonObject();
        contract.addProperty("subscription_id", sub.getId());
        JsonObject rawSubscription=JsonParser.parseString(sub.toJson()).getAsJsonObject();
        JsonObject economics=new JsonObject();
        for(String key:List.of("currency","collection_method","days_until_due","discount","discounts","default_tax_rates","automatic_tax","billing_thresholds","pending_invoice_item_interval","application_fee_percent","transfer_data","on_behalf_of"))
            if(rawSubscription.has(key))economics.add(key,rawSubscription.get(key).deepCopy());
        contract.add("subscription_economics",economics);
        contract.addProperty("original_price_id", priceId);contract.addProperty("quantity", quantity);
        contract.addProperty("cancel_at_period_end", Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));
        contract.addProperty("cancel_at", sub.getCancelAt());
        if (sub.getItems() == null || sub.getItems().getData() == null || Boolean.TRUE.equals(sub.getItems().getHasMore())) return unknown();
        JsonArray currentItems = new JsonArray();
        for (var item : sub.getItems().getData()) {
            if (item.getPrice() == null || item.getQuantity() == null) return unknown();
            JsonObject currentItem = new JsonObject();currentItem.addProperty("price_id", item.getPrice().getId());currentItem.addProperty("quantity",item.getQuantity());currentItems.add(currentItem);
        }
        contract.add("current_items",currentItems);
        if (sub.getItems().getData().size()!=1 || !priceId.equals(sub.getItems().getData().get(0).getPrice().getId())
                || !quantity.equals(sub.getItems().getData().get(0).getQuantity())) return result("FUTURE_CONTRACT_CHANGE",cancelMode,contract);
        if (sub.getSchedule() == null) return result(fixedCancellation ? "FIXED_CANCELLATION" : "NO_SCHEDULE", cancelMode, contract);
        if (schedule == null || !Objects.equals(sub.getSchedule(), schedule.getId())
                || !Objects.equals(sub.getId(), schedule.getSubscription()) || !"active".equals(schedule.getStatus())
                || schedule.getDefaultSettings() == null || schedule.getPhases() == null || schedule.getPhases().isEmpty()
                || schedule.getCurrentPhase() == null || schedule.getCurrentPhase().getStartDate() == null
                || schedule.getCurrentPhase().getEndDate() == null || schedule.getEndBehavior() == null) return unknown();
        contract.addProperty("schedule_id", schedule.getId());contract.addProperty("end_behavior", schedule.getEndBehavior());
        contract.add("default_settings", JsonParser.parseString(schedule.getDefaultSettings().toJson()));
        contract.add("current_phase", JsonParser.parseString(schedule.getCurrentPhase().toJson()));
        JsonArray phasesJson = new JsonArray();
        SubscriptionSchedule.Phase current = null;
        Long previousEnd = null;
        for (var phase : schedule.getPhases()) {
            if (phase.getStartDate() == null || phase.getEndDate() == null || phase.getEndDate() <= phase.getStartDate()
                    || (previousEnd != null && !previousEnd.equals(phase.getStartDate()))
                    || phase.getItems() == null || phase.getItems().isEmpty()) return unknown();
            previousEnd = phase.getEndDate(); phasesJson.add(JsonParser.parseString(phase.toJson()));
            if (phase.getStartDate().equals(schedule.getCurrentPhase().getStartDate())
                    && phase.getEndDate().equals(schedule.getCurrentPhase().getEndDate())) current = phase;
        }
        if (current == null) return unknown();
        contract.add("phases", phasesJson);
        if (fixedCancellation || "cancel".equals(schedule.getEndBehavior())) return result("FIXED_CANCELLATION", cancelMode, contract);
        if (!"release".equals(schedule.getEndBehavior())) return unknown();
        JsonObject currentTerms = recurringTerms(current);
        for (var phase : schedule.getPhases()) {
            if (phase.getStartDate() < current.getStartDate()) continue;
            if (phase.getItems().size() != 1 || !priceId.equals(phase.getItems().get(0).getPrice())
                    || !quantity.equals(phase.getItems().get(0).getQuantity())
                    || (phase.getTrialEnd() != null && phase.getTrialEnd() > phase.getStartDate())
                    || (phase.getStartDate() > current.getStartDate() && "phase_start".equals(phase.getBillingCycleAnchor()))
                    || !currentTerms.equals(recurringTerms(phase)))
                return result("FUTURE_CONTRACT_CHANGE", cancelMode, contract);
        }
        return result("EQUIVALENT_SCHEDULE", cancelMode, contract);
    }
    private static JsonObject recurringTerms(SubscriptionSchedule.Phase phase) {
        JsonObject terms = JsonParser.parseString(phase.toJson()).getAsJsonObject();
        // Dates and completed intro metadata remain in the original snapshot/hash, not the equivalence comparison.
        for (String key : List.of("start_date", "end_date", "metadata", "billing_cycle_anchor", "trial_end", "default_payment_method", "default_source")) terms.remove(key);
        return terms;
    }
    /** Compare the same current/future contract after Stripe drops completed phase history.
     * The original audit JSON/hash is never rewritten; every remaining field must still match. */
    static boolean sameEffectiveContract(JsonObject admitted, JsonObject current) {
        if (admitted == null || current == null) return false;
        try {
            JsonObject before = effectiveContract(admitted), after = effectiveContract(current);
            return before != null && after != null
                    && canonical(withoutControlledMethods(before)).equals(canonical(withoutControlledMethods(after)));
        } catch (RuntimeException incomplete) { return false; }
    }
    private static JsonObject effectiveContract(JsonObject contract) {
        JsonObject copy = contract.deepCopy();
        JsonObject current = copy.getAsJsonObject("current_phase");
        if (current == null || !current.has("start_date") || !current.has("end_date")) return null;
        long start = current.get("start_date").getAsLong(), end = current.get("end_date").getAsLong();
        if (end <= start || !copy.has("phases")) return null;
        JsonArray remaining = new JsonArray();boolean foundCurrent = false;Long previousEnd = null;
        for (JsonElement value : copy.getAsJsonArray("phases")) {
            JsonObject phase = value.getAsJsonObject();
            long phaseStart = phase.get("start_date").getAsLong(), phaseEnd = phase.get("end_date").getAsLong();
            if (phaseEnd <= phaseStart || previousEnd != null && previousEnd != phaseStart) return null;
            previousEnd = phaseEnd;
            if (phaseEnd <= start) continue;
            if (phaseStart < start) return null;
            if (phaseStart == start && phaseEnd == end) foundCurrent = true;
            remaining.add(phase);
        }
        if (!foundCurrent) return null;
        copy.add("phases", remaining);
        // Saved admission JSON omits null properties; the live SDK JSON may retain them.
        return JsonParser.parseString(new Gson().toJson(copy)).getAsJsonObject();
    }
    private static Result unknown() { return new Result("UNKNOWN", null, null, null); }
    private static Result result(String kind, String cancelMode, JsonObject contract) {
        String json = canonical(contract).toString();
        JsonElement hashTerms=withoutControlledMethods(contract);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(hashTerms).toString().getBytes(StandardCharsets.UTF_8)));
            return new Result(kind, cancelMode, hash, json);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static JsonElement withoutControlledMethods(JsonElement element) {
        if(element.isJsonObject()){
            JsonObject result=new JsonObject();
            element.getAsJsonObject().entrySet().forEach(e->{
                if(!Set.of("default_payment_method","default_source").contains(e.getKey()))result.add(e.getKey(),withoutControlledMethods(e.getValue()));
            });return result;
        }
        if(element.isJsonArray()){
            JsonArray result=new JsonArray();element.getAsJsonArray().forEach(v->result.add(withoutControlledMethods(v)));return result;
        }
        return element.deepCopy();
    }
    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeSet<>(element.getAsJsonObject().keySet()).forEach(key -> sorted.add(key, canonical(element.getAsJsonObject().get(key))));
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();element.getAsJsonArray().forEach(value -> array.add(canonical(value)));return array;
        }
        return element.deepCopy();
    }
}
