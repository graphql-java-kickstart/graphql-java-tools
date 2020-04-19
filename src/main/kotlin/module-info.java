module graphql.kickstart.tools {
	requires graphql.java;
	requires kotlin.stdlib;
	requires com.fasterxml.classmate;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.datatype.jdk8;

	exports graphql.kickstart.tools;
	exports graphql.kickstart.tools.directive;
	exports graphql.kickstart.tools.proxy;
	exports graphql.kickstart.tools.relay;
	exports graphql.kickstart.tools.util;

	opens graphql.kickstart.tools;
	opens graphql.kickstart.tools.directive;
	opens graphql.kickstart.tools.proxy;
	opens graphql.kickstart.tools.relay;
	opens graphql.kickstart.tools.util;
}
