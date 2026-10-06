# json2dir-groovy

json2dir in Groovy (Groovy + JDK only, hand-written JSON parser): builds the directory tree a JSON document describes.

    groovyc -d out json2dir.groovy && java -cp groovy-5.1.3.jar:out Json2dir < document.json
