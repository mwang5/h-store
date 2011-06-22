package edu.mit.hstore.interfaces;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigProperty {
    /**
     * Description of the configuration property
     * @return
     */
    String description();
    /**
     * Default String value of the configuration property
     * @return
     */
    String defaultString() default "";
    
    /**
     * 
     * @return
     */
    int defaultInt() default Integer.MIN_VALUE;
    
    /**
     * 
     * @return
     */
    long defaultLong() default Long.MIN_VALUE;
    
    /**
     * 
     * @return
     */
    double defaultDouble() default Double.MIN_VALUE;
    
    /**
     * 
     * @return
     */
    boolean defaultBoolean() default false;
    
    /**
     * Whether this configuration property is considered advanced and
     * should not be included in the default config file
     * @return
     */
    boolean advanced() default false;
    /**
     * Whether support for this configuration is considered experimental 
     * @return
     */
    boolean experimental() default false;
    /**
     * 
     * @return
     */
    boolean computed() default false;
}