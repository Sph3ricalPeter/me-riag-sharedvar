# default ID is 1
ID=1
ADDR=""
P=""

# read ID from flags
while getopts i:a:p: flag
do
  case ${flag} in
    i) ID=${OPTARG};;
    a) ADDR=${OPTARG};;
    p) P=${OPTARG};;
    *) ;;
  esac
done

# copy main file
cp "me-riag-sharedvar-1.0-SNAPSHOT.jar" "me-riag-sharedvar-1.0-SNAPSHOT_${ID}.jar"

# run
if test -z "${ADDR}" || test -z "${P}"
then
  java -jar "me-riag-sharedvar-1.0-SNAPSHOT_${ID}.jar"
else
  java -jar "me-riag-sharedvar-1.0-SNAPSHOT_${ID}.jar" "${ADDR}" "${P}"
fi