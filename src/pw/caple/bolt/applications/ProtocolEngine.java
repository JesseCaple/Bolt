package pw.caple.bolt.applications;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pw.caple.bolt.api.Client;
import pw.caple.bolt.api.Protocol;

public class ProtocolEngine {

	//TODO: search jars and other places than just bin

	private final static Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");

	private final Map<String, List<Method>> methods = new ConcurrentHashMap<>();
	private final ReservedProtocol reservedProtocol;
	private final URLClassLoader classLoader;

	public ProtocolEngine(URLClassLoader classLoader) {
		this.classLoader = classLoader;
		reservedProtocol = new ReservedProtocol(this);
		loadProtocolMethods();
	}

	private void loadProtocolMethods() {
		Reflection reflection = new Reflection(classLoader);
		for (Method method : reflection.getAnnotatedMethods(Protocol.class)) {
			if (Modifier.isStatic(method.getModifiers())) {
				if (!method.isAccessible()) {
					method.setAccessible(true);
				}
				String methodName = method.getName();
				if (!methods.containsKey(method.getName())) {
					methods.put(methodName, new CopyOnWriteArrayList<Method>());
				}
				List<Method> methodList = methods.get(methodName);
				methodList.add(method);
			} else {
				throw new RuntimeException("Error on method " + method.getName() + "() - Method should be static.");
			}
		}

		for (Method method : reservedProtocol.getClass().getDeclaredMethods()) {
			String methodName = method.getName();
			if (!methods.containsKey(method.getName())) {
				methods.put(methodName, new CopyOnWriteArrayList<Method>());
			}
			List<Method> methodList = methods.get(methodName);
			methodList.add(method);
		}

	}

	public Object processMessage(Client client, String message) {
		List<String> matches = new ArrayList<String>();
		Matcher regexMatcher = regex.matcher(message);
		while (regexMatcher.find()) {
			if (regexMatcher.group(1) != null) {
				matches.add(regexMatcher.group(1));
			} else if (regexMatcher.group(2) != null) {
				matches.add(regexMatcher.group(2));
			} else {
				matches.add(regexMatcher.group());
			}
		}
		if (matches.size() > 0) {
			String methodName = matches.get(0);
			matches.remove(0);
			String[] args = matches.toArray(new String[matches.size()]);
			if (methods.containsKey(methodName)) {
				for (Method method : methods.get(methodName)) {
					if (isTarget(method, args)) {
						return call(client, method, args);
					}
				}
				client.send("error \"" + methodName + ": wrong number of parameters\"");
			} else {
				client.send("error \"" + methodName + ": unknown command\"");
			}
		}
		return null;
	}

	private boolean isTarget(Method method, String[] args) {
		Class<?>[] params = method.getParameterTypes();
		if (params.length > 0 && Client.class.isAssignableFrom(params[0])) {
			if (method.isVarArgs() && args.length + 1 >= params.length) {
				return true;
			} else {
				return params.length == args.length + 1;
			}
		} else {
			if (method.isVarArgs() && args.length >= params.length) {
				return true;
			} else {
				return params.length == args.length;
			}
		}
	}

	private Object call(Client client, Method method, String[] given) {
		Class<?>[] params = method.getParameterTypes();
		Object[] args = new Object[params.length];
		int offset = 0;
		if (params.length > 0) {
			if (Client.class.isAssignableFrom(params[0])) {
				offset += 1;
				args[0] = client;
			}
		}
		for (int i = offset; i < params.length; i++) {
			if (method.isVarArgs() && i == params.length - 1) {
				Class<?> type = params[i].getComponentType();
				Object varArgs = Array.newInstance(type, given.length - i + offset);
				for (int givenIndex = i; givenIndex < given.length + offset; givenIndex++) {
					Object value = parseParameter(type, given[givenIndex - offset]);
					Array.set(varArgs, givenIndex - i, value);
				}
				args[i] = varArgs;
				break;
			} else {
				Object object = parseParameter(params[i], given[i - offset]);
				if (object != null) {
					args[i] = object;
				} else {
					client.send("error \"" + method.getName() + ": syntax incorrect; expected "
							+ params[i].getSimpleName() + " for parameter " + (i - offset + 1) + "\"");
					return null;
				}
			}
		}
		try {
			if (method.getDeclaringClass() == ReservedProtocol.class) {
				return method.invoke(reservedProtocol, args);
			} else {
				return method.invoke(null, args);
			}
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			e.printStackTrace();
		}
		return null;
	}

	private Object parseParameter(Class<?> type, String string) {
		if (type == String.class) {
			return string;
		} else if (type == boolean.class || type == Boolean.class) {
			if (string.equals("true") || string.equals("yes") || string.equals("1")) {
				return true;
			} else if (string.equals("false") || string.equals("no") || string.equals("0")) {
				return false;
			} else {
				return null;
			}
		} else if (type == byte.class || type == Byte.class) {
			try {
				return Byte.parseByte(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == short.class || type == Short.class) {
			try {
				return Short.parseShort(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == int.class || type == Integer.class) {
			try {
				return Integer.parseInt(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == long.class || type == Long.class) {
			try {
				return Long.parseLong(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == float.class || type == Float.class) {
			try {
				return Float.parseFloat(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == double.class || type == Double.class) {
			try {
				return Double.parseDouble(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else if (type == char.class || type == Character.class) {
			if (string.length() == 1) {
				return string.charAt(0);
			} else {
				return null;
			}
		} else if (type == Date.class) {
			try {
				long utc = Long.parseLong(string);
				return new Date(utc);
			} catch (NumberFormatException e) {}
			DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
			try {
				return format.parse(string.replace('/', '-'));
			} catch (ParseException ignore) {}
			format = new SimpleDateFormat("HH:mm:ss");
			try {
				return format.parse(string);
			} catch (ParseException ignore) {}
			return null;
		} else if (type == BigDecimal.class) {
			try {
				return new BigDecimal(string);
			} catch (NumberFormatException e) {
				return null;
			}
		} else {
			throw new RuntimeException("unsupported parameter type [" + type.getSimpleName() + "] found in a protocol method");
		}
	}

}