package com.studyagent.infra.service.billing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Currency;

/** Test-only scalar recipe. Source live IDs are evidence labels, never SDK mutation targets. */
record RecoverySandboxFixture(String caseKey,String sourceKey,String targetPlanCode,String interval,
        boolean intro,boolean keepSchedule,boolean periodEnd,String currency,long unitAmountMinor,
        long introAmountMinor,String sourcePriceId,String priceVersion,String conversionMode){
    boolean priceUpdate(){return "PRICE_UPDATE".equals(conversionMode);}
    boolean explicitFirstPay(){return "EXPLICIT_FIRST_PAY".equals(conversionMode);}
    static RecoverySandboxFixture parse(String caseKey,String json){
        var key=RecoveryCaseKey.parse(caseKey);
        try{
            JsonObject value=JsonParser.parseString(json).getAsJsonObject();
            if(value.has("caseKey") && !key.value().equals(value.get("caseKey").getAsString()))throw new IllegalArgumentException("Exact caseKey mismatch");
            String currency=value.get("currency").getAsString();
            if(!currency.matches("[a-z]{3}"))throw new IllegalArgumentException("Lowercase ISO currency required");
            Currency.getInstance(currency.toUpperCase(java.util.Locale.ROOT));
            String mode=value.has("conversionMode")?value.get("conversionMode").getAsString():"STANDARD";
            if(!java.util.Set.of("STANDARD","PRICE_UPDATE","EXPLICIT_FIRST_PAY").contains(mode)
                    || "PRICE_UPDATE".equals(mode) && ("formal".equals(key.sourceKey()) || !"NO_SCHEDULE".equals(key.scheduleClass()))
                    || "EXPLICIT_FIRST_PAY".equals(mode) && (!"formal".equals(key.sourceKey()) || !"NO_SCHEDULE".equals(key.scheduleClass())))throw new IllegalArgumentException("Unsupported fixture conversion mode");
            return new RecoverySandboxFixture(key.value(),key.sourceKey(),key.targetPlanCode(),key.interval(),!"formal".equals(key.sourceKey()),
                    "EQUIVALENT_SCHEDULE".equals(key.scheduleClass()),"PERIOD_END".equals(key.cancelMode()),currency,
                    positiveInteger(value,"unitAmountMinor"),positiveInteger(value,"introAmountMinor"),
                    text(value,"sourcePriceId"),text(value,"priceVersion"),mode);
        }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid owned-test fixture",invalid);}
    }
    private static long positiveInteger(JsonObject json,String field){
        var value=json.get(field);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.toString().matches("[0-9]+"))throw new IllegalArgumentException("Integer minor amount required: "+field);
        long amount=value.getAsLong();if(amount<=0)throw new IllegalArgumentException("Positive amount required: "+field);return amount;
    }
    private static String text(JsonObject json,String field){return json.has(field)&&!json.get(field).isJsonNull()?json.get(field).getAsString():null;}
}
