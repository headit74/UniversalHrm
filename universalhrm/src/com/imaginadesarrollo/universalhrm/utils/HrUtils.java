package com.imaginadesarrollo.universalhrm.utils;

public class HrUtils {
    public static int decode(byte[] characteristicValue){
        int bmp = characteristicValue[1] & 0xFF;
        return bmp;
    }
}
