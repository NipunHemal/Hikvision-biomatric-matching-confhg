package com.hrm.isup.model;

/** A person as managed on a device. employeeNo is the stable cross-system ID. */
public final class Person {
    public String employeeNo;
    public String name;
    public String userType = "normal";
    public String beginTime = "2026-01-01T00:00:00";
    public String endTime = "2030-12-31T23:59:59";
    public int doorNo = 1;
    public String pin;   // access PIN / password (optional)

    public Person() {}

    public Person(String employeeNo, String name) {
        this.employeeNo = employeeNo;
        this.name = name;
    }
}
