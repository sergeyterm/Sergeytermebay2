package com.personal.sonyvegasvideo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Covers persistence/history independence and hostile/out-of-range parameter values. */
public final class EffectSpecTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        EffectSpec clean=new EffectSpec();check(clean.isIdentity());check(clean.equals(clean.copy()));check(clean.hashCode()==clean.copy().hashCode());
        for(Field field:EffectSpec.class.getFields()){
            if(Modifier.isStatic(field.getModifiers()))continue;EffectSpec changed=clean.copy();
            if(field.getType()==float.class)field.setFloat(changed,field.getFloat(changed)+.125f);
            else if(field.getType()==int.class)field.setInt(changed,field.getInt(changed)+1);
            else if(field.getType()==boolean.class)field.setBoolean(changed,!field.getBoolean(changed));
            else if(field.getType()==String.class)field.set(changed,field.get(changed)+"x");
            else throw new AssertionError("Unsupported reference field "+field);
            check(!changed.equals(clean));check(changed.copy().equals(changed));check(clean.equals(new EffectSpec()));
        }
        EffectSpec outside=new EffectSpec();outside.brightness=Float.NaN;outside.contrast=Float.POSITIVE_INFINITY;outside.blur=-30;outside.saturation=200;outside.titleSize=2;outside.overlayScale=-1;outside.masterGain=Float.NEGATIVE_INFINITY;outside.transitionMs=Integer.MAX_VALUE;outside.preset="unknown";outside.transition=null;outside.title=null;outside.audioUri=null;outside.overlayUri=null;
        outside.normalize();check(outside.brightness==0&&outside.contrast==0&&outside.blur==0);check(outside.saturation==2&&outside.titleSize==.2f);check(outside.overlayScale==.1f&&outside.masterGain==1);check(outside.transitionMs==5000);check(outside.preset.equals("none")&&outside.transition.equals("none"));check(outside.title.isEmpty()&&outside.audioUri.isEmpty()&&outside.overlayUri.isEmpty());
        EffectSpec parameters=new EffectSpec();parameters.title=new String(new char[250]).replace('\0','x');parameters.audioUri=new String(new char[9000]).replace('\0','x');parameters.chromaColor=0x00123456;parameters.normalize();check(parameters.title.length()==200&&parameters.audioUri.isEmpty());check(parameters.chromaColor==0xff123456);
        EffectSpec signed=clean.copy();signed.brightness=-0f;check(!signed.equals(clean));check(signed.equals(signed.copy())&&signed.hashCode()==signed.copy().hashCode());
        for(String preset:new String[]{"none","mono","sepia","vivid","cool","warm","invert","vintage"}){EffectSpec spec=clean.copy();spec.preset=preset;check(spec.normalize().preset.equals(preset));}
        for(String transition:new String[]{"none","dissolve","cross","wipe","cascade"}){EffectSpec spec=clean.copy();spec.transition=transition;check(spec.normalize().transition.equals(transition));}
        EffectSpec title=clean.copy();title.title="Hello";check(!title.isIdentity());check(clean.isIdentity());title.copy().normalize().title="Changed";check(title.title.equals("Hello"));
        EffectSpec latent=clean.copy();latent.transitionMs=1500;latent.chromaThreshold=.6f;check(latent.isIdentity());
        System.out.println("EffectSpecTest OK: "+checks+" checks");
    }
    private static void check(boolean valid){checks++;if(!valid)throw new AssertionError("EffectSpec check "+checks);}
}
