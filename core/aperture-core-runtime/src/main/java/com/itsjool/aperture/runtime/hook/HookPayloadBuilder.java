package com.itsjool.aperture.runtime.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

import java.lang.reflect.Field;
import java.util.*;

public class HookPayloadBuilder {

    // Fields that must never be overwritten by an enrichment hook response
    private static final Set<String> PROTECTED_FIELDS = Set.of(
        "id", "apertureTenantId", "version", "deletedAt"
    );

    public static String build(Object entity, ObjectMapper om) {
        Map<String, Object> payload = new LinkedHashMap<>();
        collectFields(entity.getClass(), payload, entity);
        try {
            return om.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void collectFields(Class<?> cls, Map<String, Object> payload, Object entity) {
        if (cls == null || cls == Object.class) return;
        collectFields(cls.getSuperclass(), payload, entity);
        for (Field f : cls.getDeclaredFields()) {
            if (f.isAnnotationPresent(OneToMany.class) || f.isAnnotationPresent(ManyToMany.class)) continue;
            f.setAccessible(true);
            try {
                if (f.isAnnotationPresent(ManyToOne.class)) {
                    Object related = f.get(entity);
                    payload.put(f.getName() + "_id", related != null ? extractId(related) : null);
                } else {
                    payload.put(f.getName(), f.get(entity));
                }
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static Object extractId(Object entity) {
        for (Field f : getAllFields(entity.getClass())) {
            if (f.isAnnotationPresent(jakarta.persistence.Id.class)) {
                f.setAccessible(true);
                try { return f.get(entity); } catch (IllegalAccessException ignored) {}
            }
        }
        return null;
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            fields.addAll(Arrays.asList(cls.getDeclaredFields()));
            cls = cls.getSuperclass();
        }
        return fields;
    }

    public static void applyEnrichmentOverrides(Object entity, String responseBody, ObjectMapper om) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode attrs = root.path("data").path("attributes");
            if (!attrs.isObject()) return;
            attrs.fields().forEachRemaining(entry -> {
                String attrKey = entry.getKey();
                if (PROTECTED_FIELDS.contains(attrKey)) return;
                String setterName = "set" + Character.toUpperCase(attrKey.charAt(0)) + attrKey.substring(1);
                for (java.lang.reflect.Method m : entity.getClass().getMethods()) {
                    if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                        try {
                            Class<?> paramType = m.getParameterTypes()[0];
                            Object value = entry.getValue().isNull() ? null : om.convertValue(entry.getValue(), paramType);
                            m.invoke(entity, value);
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            });
        } catch (Exception ignored) {}
    }
}
