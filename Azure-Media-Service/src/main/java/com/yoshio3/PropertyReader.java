/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.yoshio3;

import java.util.ResourceBundle;

/**
 *
 * @author yoterada
 */
public class PropertyReader {
    private final static String APP_RESOURCES = "app-resources";

    public static String getPropertyValue(String key) {
        ResourceBundle resources = ResourceBundle.getBundle(APP_RESOURCES);
        String value = "";
        if (resources.containsKey(key)) {
            value = resources.getString(key);
        }
        return value;   
    }
}
