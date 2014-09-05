package com.iwebpp.node;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public abstract class Writable2 
extends EventEmitter2 
implements Writable {
	private final static String TAG = "Writable2";

	public static class WriteReq {
		/**
		 * @return the chunk
		 */
		public Object getChunk() {
			return chunk;
		}
		/**
		 * @return the encoding
		 */
		public String getEncoding() {
			return encoding;
		}
		/**
		 * @return the callback
		 */
		public WriteCB getCallback() {
			return callback;
		}
		private Object chunk;
		private String encoding;
		private WriteCB callback;

		public WriteReq(Object chunk, String encoding, WriteCB cb) {
			this.chunk = chunk;
			this.encoding = encoding;
			this.callback = cb;
		}
		@SuppressWarnings("unused")
		private WriteReq() {
		}
	}

	public static class Options {
		/**
		 * @return the highWaterMark
		 */
		public int getHighWaterMark() {
			return highWaterMark;
		}
		/**
		 * @return the objectMode
		 */
		public boolean isObjectMode() {
			return objectMode;
		}
		/**
		 * @return the decodeStrings
		 */
		public boolean isDecodeStrings() {
			return decodeStrings;
		}
		/**
		 * @return the defaultEncoding
		 */
		public String getDefaultEncoding() {
			return defaultEncoding;
		}
		private int highWaterMark;
		private boolean objectMode;
		private boolean decodeStrings;
		private String defaultEncoding;
		
		public Options(
				int highWaterMark,
				boolean decodeStrings,
				String defaultEncoding,
				boolean objectMode) {
			this.highWaterMark = highWaterMark;
			this.objectMode = objectMode;
			this.decodeStrings = decodeStrings;
			this.defaultEncoding = defaultEncoding;
		}
		@SuppressWarnings("unused")
		private Options(){}
	}

	public class State {
		ArrayList<WriteReq> buffer;
		boolean objectMode;
		int highWaterMark;
		boolean needDrain;
		boolean ending;
		boolean ended;
		boolean finished;
		boolean decodeStrings;
		String defaultEncoding;
		int length;
		boolean writing;
		int corked;
		boolean sync;
		boolean bufferProcessing;
		WriteCB onwrite;
		WriteCB writecb;
		int writelen;
		int pendingcb;
		boolean prefinished;
		boolean errorEmitted;


		public State(Options options, final Writable2 stream) {

			// object stream flag to indicate whether or not this stream
			// contains buffers or objects.
			this.objectMode = options.objectMode;
			
			// TBD...
			///if (stream instanceof Stream.Duplex)
			///	this.objectMode = this.objectMode || !!options.writableObjectMode;

			// the point at which write() starts returning false
			// Note: 0 is a valid value, means that we always return false if
			// the entire buffer is not flushed immediately on write()
			int hwm = options.highWaterMark;
			int defaultHwm = this.objectMode ? 16 : 16 * 1024;
			this.highWaterMark = (hwm >= 0) ? hwm : defaultHwm;

			// cast to ints.
			///this.highWaterMark = ~~this.highWaterMark;

			this.needDrain = false;
			// at the start of calling end()
			this.ending = false;
			// when end() has been called, and returned
			this.ended = false;
			// when 'finish' is emitted
			this.finished = false;

			// should we decode strings into buffers before passing to _write?
			// this is here so that some node-core streams can optimize string
			// handling at a lower level.
			boolean noDecode = options.decodeStrings == false;
			this.decodeStrings = !noDecode;

			// Crypto is kind of old and crusty.  Historically, its default string
			// encoding is 'binary' so we have to make this configurable.
			// Everything else in the universe uses 'utf8', though.
			this.defaultEncoding = options.defaultEncoding != null ? options.defaultEncoding : "UTF-8";

			// not an actual buffer we keep track of, but a measurement
			// of how much we're waiting to get pushed to some underlying
			// socket or file.
			this.length = 0;

			// a flag to see when we're in the middle of a write.
			this.writing = false;

			// when true all writes will be buffered until .uncork() call
			this.corked = 0;

			// a flag to be able to tell if the onwrite cb is called immediately,
			// or on a later tick.  We set this to true at first, because any
			// actions that shouldn't happen until "later" should generally also
			// not happen before the first write call.
			this.sync = true;

			// a flag to know if we're processing previously buffered items, which
			// may call the _write() callback in the same tick, so that we don't
			// end up in an overlapped onwrite situation.
			this.bufferProcessing = false;

			// the callback that's passed to _write(chunk,cb)
			this.onwrite = new WriteCB() {
				@Override
				public void invoke(String error) throws Exception {
					onwrite(stream, error);
				}
			};

			// the callback that the user supplies to write(chunk,encoding,cb)
			this.writecb = null;

			// the amount that is being written when _write is called.
			this.writelen = 0;

			// WriteReq buffer
			this.buffer = new ArrayList<WriteReq>();

			// number of pending user-supplied write callbacks
			// this must be 0 before 'finish' can be emitted
			this.pendingcb = 0;

			// emit prefinish if the only thing we're waiting for is _write cbs
			// This is relevant for synchronous Transform streams
			this.prefinished = false;
		}
	}

	// _write(chunk, encoding, callback)
	public abstract void _write(Object chunk, String encoding, WriteCB cb) throws Exception;

	protected State _writableState;
	
	public boolean isNeedDrain() {
		return _writableState.needDrain;
	}

	private boolean writable;
	
    protected Writable2(Options options) {
    	super();
    	
	  // Writable ctor is applied to Duplexes, though they're not
	  // instanceof Writable, they're instanceof Readable.
	  ///if (!(this instanceof Writable) && !(this instanceof Stream.Duplex))
	  ///  return new Writable(options);

	  this._writableState = new State(options, this);

	  // legacy.
	  this.writable = true;
    }
    @SuppressWarnings("unused")
	private Writable2() {
	}
    
    public boolean writable() { 
    	return writable;
    }
    
    // Helpers functions
    private static void writeAfterEnd(Writable2 stream, State state, WriteCB cb) throws Exception {
    	///var er = new Error('write after end');
    	// TODO: defer error events consistently everywhere, not just the cb
    	stream.emit("error", "write after end");
    	//TBD...
    	///process.nextTick(function() {
    	cb.invoke("write after end");
    	///});
    }

    // If we get something that is not a buffer, string, null, or undefined,
    // and we're not in objectMode, then that's an error.
    // Otherwise stream chunks are all considered to be of length=1, and the
    // watermarks determine how many objects to keep in the buffer, rather than
    // how many bytes or characters.
    private static boolean validChunk(Writable2 stream, State state, Object chunk, WriteCB cb) throws Exception {
    	boolean valid = true;
    	if (!Util.isBuffer(chunk) &&
    		!Util.isString(chunk) &&
    		!Util.isNullOrUndefined(chunk) &&
    		!state.objectMode) {
    		///var er = new TypeError('Invalid non-string/buffer chunk');
    		String er = "Invalid non-string/buffer chunk";
    		stream.emit("error", er);
    		//TBD...
    		///process.nextTick(function() {
    			cb.invoke(er);
    		///});
    		valid = false;
    	}
    	return valid;
    }

    public boolean write(Object chunk, String encoding, WriteCB cb) throws Exception {
    	State state = this._writableState;
    	boolean ret = false;

    	/*if (util.isFunction(encoding)) {
    	    cb = encoding;
    	    encoding = null;
    	  }*/

    	if (Util.isBuffer(chunk))
    		encoding = "buffer";
    	else if (Util.zeroString(encoding))
    		encoding = state.defaultEncoding;

    	///if (!util.isFunction(cb))
    	///	cb = function() {};
    	if (cb == null)
    		cb = new WriteCB()
    	{
    		@Override
    		public void invoke(String error) {
    			// TODO Auto-generated method stub
    		}
    	};

    	if (state.ended)
    		writeAfterEnd(this, state, cb);
    	else if (validChunk(this, state, chunk, cb)) {
    		state.pendingcb++;
    		ret = writeOrBuffer(this, state, chunk, encoding, cb);
    	}

    	return ret;
    }

    public void end(Object chunk, String encoding, WriteCB cb) throws Exception {
    	State state = this._writableState;

    	/*if (util.isFunction(chunk)) {
    		cb = chunk;
    		chunk = null;
    		encoding = null;
    	} else if (util.isFunction(encoding)) {
    		cb = encoding;
    		encoding = null;
    	}*/

    	///if (!util.isNullOrUndefined(chunk))
    	if (chunk != null)
    		this.write(chunk, encoding, null);

    	// .end() fully uncorks
    	if (state.corked != 0) {
    		state.corked = 1;
    		this.uncork();
    	}

    	// ignore unnecessary end() calls.
    	if (!state.ending && !state.finished)
    		endWritable(this, state, cb);
    }

    private void endWritable(Writable2 stream, State state, final WriteCB cb) throws Exception {
    	state.ending = true;
    	finishMaybe(stream, state);
    	if (cb != null) {
    		if (state.finished)
    			///process.nextTick(cb);
    			cb.invoke(null);
    		else
    			stream.once("finish", new EventEmitter.Listener() {
    				@Override
    				public void invoke(Object data) throws Exception {
    					// TODO Auto-generated method stub
    					cb.invoke(null);
    				}
    			});
    	}
    	state.ended = true;
    }

    protected void cork() {
    	State state = this._writableState;

    	state.corked++;
    }

    protected void uncork() throws Exception {
    	State state = this._writableState;

    	if (state.corked > 0) {
    		state.corked--;

    		if (!state.writing &&
    			 state.corked == 0 &&
    			!state.finished &&
    			!state.bufferProcessing &&
    			 state.buffer.size() > 0)
    			clearBuffer(this, state);
    	}
    }
    
    // if we're already writing something, then just put this
    // in the queue, and wait our turn.  Otherwise, call _write
    // If we return false, then we need a drain event, so set that flag.
    private static boolean writeOrBuffer(Writable2 stream, State state,
    		Object chunk, String encoding, WriteCB cb) throws Exception {
    	chunk = decodeChunk(state, chunk, encoding);
    	if (Util.isBuffer(chunk))
    		encoding = "buffer";
    	int len = state.objectMode ? 1 : Util.chunkLength(chunk);

    	state.length += len;

    	boolean ret = state.length < state.highWaterMark;
    	// we must ensure that previous needDrain will not be reset to false.
    	if (!ret)
    		state.needDrain = true;

    	if (state.writing || state.corked != 0)
    		state.buffer.add(new WriteReq(chunk, encoding, cb));
    	else
    		doWrite(stream, state, false, len, chunk, encoding, cb);

    	return ret;
    }

    private static Object decodeChunk(State state, Object chunk, String encoding) throws Exception {
    	if (!state.objectMode &&
    		 state.decodeStrings != false &&
    		 Util.isString(chunk)) {
    		chunk = ByteBuffer.wrap(((String)chunk).getBytes(encoding));
    	}
    	return chunk;
    }

	private static void onwrite(Writable2 stream, String error) throws Exception {
		State state = stream._writableState;
		boolean sync = state.sync;
		WriteCB cb = state.writecb;

		onwriteStateUpdate(state);

		if (error != null)
			onwriteError(stream, state, sync, error, cb);
		else {
			// Check if we're actually ready to finish, but don't emit yet
			boolean finished = needFinish(stream, state);

			if (!finished &&
					state.corked == 0 &&
					!state.bufferProcessing &&
					state.buffer.size() > 0) {
				clearBuffer(stream, state);
			}

			if (sync) {
				///TBD
				///process.nextTick(function() {
				afterWrite(stream, state, finished, cb);
				///});
			} else {
				afterWrite(stream, state, finished, cb);
			}
		}
	}

	// if there's something in the buffer waiting, then process it
	private static void clearBuffer(Writable2 stream, State state) throws Exception {
		state.bufferProcessing = true;

		/*if (stream._writev && state.buffer.length > 1) {
			    // Fast case, write everything using _writev()
			    var cbs = [];
			    for (var c = 0; c < state.buffer.length; c++)
			      cbs.push(state.buffer[c].callback);

			    // count the one we are adding, as well.
			    // TODO(isaacs) clean this up
			    state.pendingcb++;
			    doWrite(stream, state, true, state.length, state.buffer, '', function(err) {
			      for (var i = 0; i < cbs.length; i++) {
			        state.pendingcb--;
			        cbs[i](err);
			      }
			    });

			    // Clear buffer
			    state.buffer = [];
			  } else */
		{
			// Slow case, write chunks one-by-one
			int c = 0;
			for (c = 0; c < state.buffer.size(); c++) {
				WriteReq entry = state.buffer.get(c);
				Object chunk = entry.chunk;
				String encoding = entry.encoding;
				WriteCB cb = entry.callback;
				int len = state.objectMode ? 1 : Util.chunkLength(chunk);

					doWrite(stream, state, false, len, chunk, encoding, cb);

					// if we didn't call the onwrite immediately, then
					// it means that we need to wait until it does.
					// also, that means that the chunk and cb are currently
					// being processed, so move the buffer counter past them.
					if (state.writing) {
						c++;
						break;
					}
			}

			if (c < state.buffer.size())
				state.buffer = (ArrayList<WriteReq>) state.buffer.subList(c, state.buffer.size());
			else
				state.buffer.clear();
		}

		state.bufferProcessing = false;
	}

	private static void doWrite(Writable2 stream, State state, boolean b,
			int len, Object chunk, String encoding, WriteCB cb) throws Exception {
		state.writelen = len;
		state.writecb = cb;
		state.writing = true;
		state.sync = true;
		/*if (writev)
			stream._writev(chunk, state.onwrite);
		else*/
		stream._write(chunk, encoding, state.onwrite);
		state.sync = false;
	}

	private static void afterWrite(Writable2 stream, State state,
			boolean finished, WriteCB cb) throws Exception {
		if (!finished)
			onwriteDrain(stream, state);
		state.pendingcb--;
		cb.invoke(null);
		finishMaybe(stream, state);
	}

	private static boolean finishMaybe(Writable2 stream, State state) throws Exception {
		boolean need = needFinish(stream, state);
		if (need) {
			if (state.pendingcb == 0) {
				prefinish(stream, state);
				state.finished = true;
				stream.emit("finish");
			} else
				prefinish(stream, state);
		}
		return need;
	}

	private static void prefinish(Writable2 stream, State state) throws Exception {
		if (!state.prefinished) {
			state.prefinished = true;
			stream.emit("prefinish");
		}
	}

	// Must force callback to be called on nextTick, so that we don't
	// emit 'drain' before the write() consumer gets the 'false' return
	// value, and has a chance to attach a 'drain' listener.
	private static void onwriteDrain(Writable2 stream, State state) throws Exception {
		if (state.length == 0 && state.needDrain) {
			state.needDrain = false;
			stream.emit("drain");
		}
	}

	private static boolean needFinish(Writable2 stream, State state) {
		return (state.ending &&
				state.length == 0 &&
				state.buffer.size() == 0 &&
				!state.finished &&
				!state.writing);
	}

	private static void onwriteError(Writable2 stream, State state,
			boolean sync, String error, WriteCB cb) throws Exception {
		if (sync) {
			/// TBD
			///process.nextTick(function() {
			state.pendingcb--;
			cb.invoke(error);
			///});
		} else {
			state.pendingcb--;
			cb.invoke(error);
		}

		stream.emit("error", error);
	}

	private static void onwriteStateUpdate(State state) {
		state.writing = false;
		state.writecb = null;
		state.length -= state.writelen;
		state.writelen = 0;
	}

}
