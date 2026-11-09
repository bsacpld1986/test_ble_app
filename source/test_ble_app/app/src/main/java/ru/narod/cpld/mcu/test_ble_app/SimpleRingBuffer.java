/*

Copyright (c) 2007-2026 Sergey Bezzubov (bsa-one@yandex.ru)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

*/

//-------------------------------------------------------------------------//
// File:        "SimpleRingBuffer.java"                                    //
// Project:     "test_ble_app"                                             //
// Description: BLE ring buffer                                            //
//                                                                         //
// Author:      Sergey Bezzubov (bsa-one@yandex.ru)                        //
// 09.11.2026                                                              //
//-------------------------------------------------------------------------//

package ru.narod.cpld.mcu.test_ble_app;

import java.util.Arrays;

public class SimpleRingBuffer<T> {
    private T[] buffer;
    private int head; // Read position
    private int tail; // Write position
    private int capacity;
    private int size; // Number of elements currently in the buffer

    @SuppressWarnings("unchecked")
    public SimpleRingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    // Adds an element to the buffer.
    // If the buffer is full, the oldest element is overwritten.
    public void put(T item) {
        buffer[tail] = item;
        tail = (tail + 1) % capacity; // Move tail, wrap around if needed

        if (size == capacity) {
            // Buffer is full, head must also advance to overwrite the oldest element
            head = (head + 1) % capacity;
        } else {
            size++; // Increase size only if not overwriting
        }
    }

    // Removes and returns the oldest element from the buffer.
    // Returns null if the buffer is empty.
    public T get() {
        if (size == 0) {
            return null; // Buffer is empty
        }
        T item = buffer[head];
        buffer[head] = null; // Optional: help garbage collection
        head = (head + 1) % capacity; // Move head, wrap around if needed
        size--;
        return item;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == capacity;
    }

	public void flush() {
		for(int i=0 ; i<capacity ; i++)
		{
			buffer[i] = null;
		}
        head = 0;
        tail = 0;
        size = 0;
    }

    @Override
    public String toString() {
        return "SimpleRingBuffer{" +
			"buffer=" + Arrays.toString(buffer) +
			", head=" + head +
			", tail=" + tail +
			", size=" + size +
			", capacity=" + capacity +
			'}';
    }
}
