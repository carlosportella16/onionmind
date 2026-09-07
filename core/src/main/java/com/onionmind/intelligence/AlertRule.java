package com.onionmind.intelligence;

record AlertRule(Long id, AlertCriteriaType criteriaType, String criteriaValue, boolean active) {
}
