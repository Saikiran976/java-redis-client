package nl.melp.redis;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * A lightweight implementation of the nl.melp.redis.Redis server protocol.
 *
 * Effectively a complete nl.melp.redis.Redis client implementation.
 */
public class Redis {
	public static class Encoder {
		private static byte[] CRLF = new byte[]{'\r', '\n'};
		private final OutputStream out;

		public Encoder(OutputStream out) {
			this.out = out;
		}

		public void write(String s) throws IOException {
			byte[] value = s.getBytes();
			out.write('$');
			out.write(Long.toString(value.length).getBytes());
			out.write(CRLF);
			out.write(value);
			out.write(CRLF);
		}

		public void write(long v) throws IOException {
			out.write(':');
			out.write(Long.toString(v).getBytes());
			out.write(CRLF);
		}

		public void write(List<Object> list) throws IOException {
			out.write('*');
			out.write(Long.toString(list.size()).getBytes());
			out.write(CRLF);

			for (Object o : list) {
				if (o instanceof String) {
					write((String)o);
				} else if (o instanceof Long) {
					write((Long)o);
				} else if (o instanceof Integer) {
					write(((Integer) o).longValue());
				} else if (o instanceof List) {
					write((List)o);
				} else {
					throw new IllegalArgumentException("Unexpected type " + o.getClass().getCanonicalName());
				}
			}
			out.flush();
		}
	}

	public static class Parser {
		public static class ParseException extends RuntimeException {
			public ParseException(String msg) {
				super(msg);
			}
		}

		public static class ServerError extends RuntimeException {
			public ServerError(String msg) {
				super(msg);
			}
		}

		private final InputStream input;

		public Parser(InputStream input) {
			this.input = input;
		}

		public Object parse() throws IOException {
			Object ret;
			switch (this.input.read()) {
				case '+':
					ret = this.parseSimpleString();
					break;
				case '-':
					throw new ServerError(this.parseSimpleString());
				case ':':
					ret = this.parseNumber();
					break;
				case '$':
					ret = this.parseBulkString();
					break;
				case '*':
					long len = this.parseNumber();
					if (len == -1) {
						ret = null;
					} else {
						List<Object> arr = new LinkedList<>();
						for (long i = 0; i < len; i ++) {
							arr.add(this.parse());
						}
						ret = arr;
					}
					break;
				default:
					throw new ParseException("Unexpected input");
			}

			return ret;
		}

		private String parseBulkString() throws IOException {
			long p = parseNumber();
			if (p > Integer.MAX_VALUE) {
				throw new ParseException("Unsupported value length for bulk string");
			}
			if (p == -1) {
				return null;
			}
			return new String(scanCr((int)p));
		}

		private String parseSimpleString() throws IOException {
			return new String(scanCr(1024));
		}

		private long parseNumber() throws IOException {
			return Long.valueOf(new String(scanCr(1024)));
		}

		private byte[] scanCr(int size) throws IOException {
			int idx = 0;
			int ch;
			byte[] buffer = new byte[size];
			while ((ch = input.read()) != '\r') {
				buffer[idx ++] = (byte)ch;
				if (idx == size) {
					// increase buffer size.
					size *= 2;
					buffer = java.util.Arrays.copyOf(buffer, size);
				}
			}
			if (input.read() != '\n') {
				throw new ParseException("Expected LF");
			}

			return Arrays.copyOfRange(buffer, 0, idx);
		}
	}

	private final Encoder writer;
	private final Parser reader;

	public Redis(Socket socket) throws IOException {
		this.writer = new Encoder(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
		this.reader = new Parser(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
	}

	public <T> T call(String... args) throws IOException {
		writer.write(Arrays.asList(args));
		return (T)reader.parse();
	}

	public static abstract class Pipeline {
		public abstract Pipeline call(String... args) throws IOException;
		public abstract List<Object> read() throws IOException;
	}

	public Pipeline pipeline() {
		return new Pipeline() {
			private int n = 0;

			public Pipeline call(String... args) throws IOException {
				writer.write(Arrays.asList(args));
				n ++;
				return this;
			}

			public List<Object> read() throws IOException {
				List<Object> ret = new LinkedList<>();
				while (n -- > 0) {
					ret.add(reader.parse());
				}
				return ret;
			}
		};
	}
}