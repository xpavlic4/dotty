
/*
 * Copyright (C) 2012-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package scala.compat.java8;

@FunctionalInterface
public interface JFunction20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> extends scala.Function20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> {
    default void $init$() {
    };

    @SuppressWarnings("unchecked")
    default scala.Function1<T1, scala.Function1<T2, scala.Function1<T3, scala.Function1<T4, scala.Function1<T5, scala.Function1<T6, scala.Function1<T7, scala.Function1<T8, scala.Function1<T9, scala.Function1<T10, scala.Function1<T11, scala.Function1<T12, scala.Function1<T13, scala.Function1<T14, scala.Function1<T15, scala.Function1<T16, scala.Function1<T17, scala.Function1<T18, scala.Function1<T19, scala.Function1<T20, R>>>>>>>>>>>>>>>>>>>> curried() {
      return scala.Function20$class.curried(this);
    }

    @SuppressWarnings("unchecked")
    default scala.Function1<scala.Tuple20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20>, R> tupled() {
      return scala.Function20$class.tupled(this);
    }


}
