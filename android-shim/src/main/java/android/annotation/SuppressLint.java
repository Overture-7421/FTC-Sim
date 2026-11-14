package android.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Tells lint to ignore the named checks. It carries no run-time meaning; it is here so annotated SDK classes compile. */
@Target({
    ElementType.TYPE,
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.CONSTRUCTOR,
    ElementType.LOCAL_VARIABLE
})
@Retention(RetentionPolicy.CLASS)
public @interface SuppressLint {
    String[] value();
}
