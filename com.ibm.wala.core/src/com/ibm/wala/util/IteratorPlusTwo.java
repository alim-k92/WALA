/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.util;

import java.util.Iterator;

import com.ibm.wala.util.debug.UnimplementedError;

public class IteratorPlusTwo<T> implements Iterator<T> {
  private final Iterator<T> it;
  
  // the following fields will be nulled out after visiting xtra.
  private T xtra1;
  private T xtra2;
  
  public IteratorPlusTwo(Iterator<T> it, T xtra1, T xtra2) {
    this.it = it;
    this.xtra1 = xtra1;
    this.xtra2 = xtra2;
  }
  
  /* (non-Javadoc)
   * @see java.util.Iterator#hasNext()
   */
  public boolean hasNext() {
    return it.hasNext() || (xtra1 != null) || (xtra2 != null);
  }

  /* (non-Javadoc)
   * @see java.util.Iterator#next()
   */
  public T next() {
    if (it.hasNext()) {
      return it.next();
    } else if (xtra1 != null) {
      T result = xtra1;
      xtra1 = null;
      return result;
    } else {
      T result = xtra2;
      xtra2 = null;
      return result;
    }
  }
   
  /* (non-Javadoc)
   * @see java.util.Iterator#remove()
   */
  public void remove() throws UnimplementedError {
    throw new UnimplementedError();
  }

}
