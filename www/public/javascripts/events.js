$(document).ready(function() {

    var source = null

    function closeSource(){
        if(source != null) {
            console.log("Closing source")
            source.close()
        }
    }

    function init() {
        readFeed()
    }

    function readFeed() {
       var url = feedUrl()
       closeSource()
       source = new EventSource(url)
       source.onopen = function(event) {
            console.log("Feed " + url)
        }
       source.onmessage = function(evt) {
            $('#spinner').hide()
            if(!evt.data || 0 === evt.data.length) return
            try {
                //console.log("onMessage event=" + JSON.stringify(evt.data, null, 4))
                writeEvent(evt.data)
            } catch (exception) {
                console.log(exception)
            }
        }
        source.onerror = function(evt) {
            console.log("onError event=" + JSON.stringify(evt, null, 4))
        }
    }

    function writeEvent(message) {
        if($.isEmptyObject(JSON.parse(message))) return
        var event = new Event(message)
        if(event.type=='Await' || event.type=='Close'){
            if ($('#events tr').length == 0)
                $('#events').prepend("<tr><td style=\"text-align: center;font-size: large;background: rgba(0, 0, 0, 0); margin:0 auto;\">No data</td></tr>")
            if (event.type=='Close') closeSource()
            return
        }
        var isJson = !isString(event.data)
        var dataDiv = event.message
        var id = makeid()
        if (event.data != '') {
            dataDiv = event.message==''?'details...':event.message
        }
        var color = getColor(dataDiv)
        var content = '<tr id="item-'+id+'"><td class="service filter" id="'+event.name+'"><a href=#>'+event.name+'</a></td><td class="version filter" id="'+event.name+'" version="'+event.version+'"><a href=#>'+event.version+'</a></td><td class="type">'+event.type+'</td><td class="timestamp">'+event.timestamp+'</td><td class="details" id="expand-'+id+'" style="color:'+color+'">'+dataDiv+'</td><td class="user">'+event.user+'</td></tr>'
        if(isJson) {
            content = content + '<tr id="content-'+id+'" style=\"display:none;\"><td colspan=6 class="msg"><pre>'+library.json.prettyPrint(event.data)+'</pre></td></tr>'
        }
        $('#events').prepend(content)
        if (isJson) {
            $('#expand-'+id).click(function(){
                $('#content-'+id).toggle();
                })
        }
        $(".filter").click(function() {filterByServiceAndVersion($(this).attr('id'), $(this).attr('version'))})
    }

    function feedUrl() {
        var service = readService($('#service').val())
        var from = readTimestamp($('#date_timepicker_start').val())
        var to = readTimestamp($('#date_timepicker_end').val())
        var version = readVersion($('#version').val())
        var url = '/feed'
        var params = document.location.search
        params = addUrlParam(params, 'from', from)
        params = addUrlParam(params, 'to', to)
        params = addUrlParam(params, 'service', service)
        params = addUrlParam(params, 'version', version)
        if (params.trim().length>0)
            url += params
        //console.log(url)
        return url
    }

    var addUrlParam = function(search, key, val){
      if(val == null) return search
      var newParam = key + '=' + val,
          params = '?' + newParam
      if (search) {
        params = search.replace(new RegExp('[\?&]' + key + '[^&]*'), '$1' + newParam)
        if (params === search) {
          params += '&' + newParam
        }
      }
      return params
    }

    function readTimestamp(dateString) {
        try {
            return Date.parseDate(dateString, 'Y/m/d H:i').getTime()*1000
         } catch (exception) {
            if(dateString != '____/__/__ __:__')
                console.log(exception)
            return null
         }
    }

    function readService(serviceString) {
          var cleanStr = serviceString.replace(/\s/g, '')
          if (cleanStr.length == 0) return null
          return cleanStr//.split(",")
    }

    function readVersion(versionString) {
              var versionString = versionString.replace(/\s/g, '')
              if (versionString.length == 0) return null
              return versionString
        }

    function makeid() {
        var text = ""
        var possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        for (var i=0; i < 5; i++ )
            text += possible.charAt(Math.floor(Math.random() * possible.length))
        return text
    }

    function isString(data) {
        if(typeof data == "string")
            return true
        return false
    }

    function filterByServiceAndVersion(name, version) {
        $('#service').val(name)
        if(version != null)
            $('#version').val(version)
        else
            $('#version').val('')
        $("#search").click()
    }

    function getColor(content) {
        try {
            var regexp = /to\s+(RED|GREEN|YELLOW|GREY)+/g
            var color = regexp.exec(content)
            if (color == null) color = "black"
            else color = color[1]
            color = color.toLowerCase()
            if(color == "yellow") color = "#D4C60D"
            return color
        } catch (ex) {
            console.log(ex)
        }
        return "black"
    }

    function Event (eventMessage) {
        var event = JSON.parse(eventMessage)
        this.name = getOrElse(event.service, '')
        this.version = getOrElse(event.version, '')
        this.type = formatEventType(getOrElse(event.type, ''))
        this.timestamp = formatDate(getOrElse(event.timestamp, ''))
        this.data = getOrElse(event.data, '')
        this.message = getOrElse(event.message, '')
        this.user = getOrElse(event.user, '')
    }

    function getOrElse(value, defaultVal) {
        try {
            if(typeof value != "undefined" && value != null) {
                return value
            }
            return defaultVal
        } catch (exception) {
            console.log(exception)
            return defaultVal
        }
    }

    function formatDate(timestamp) {
        try {
            return $.format.date(new Date(Number(timestamp/1000)), "HH:mm:ss dd/MMM/yy")
        } catch (exception) {
            console.log(exception)
            return timestamp
        }
    }

    function formatEventType(eventType) {
            try {
               var type = eventType.split(/(?=[A-Z])/).join(" ").toLowerCase()
               return type.charAt(0).toUpperCase() + type.slice(1)
            } catch (exception) {
                console.log(exception)
                return eventType
            }
        }

    function getDatetime(dateStr, format) {
        if(dateStr.length==0) return false
            try {
                var timestamp = readTimestamp(dateStr)
                if(timestamp == null) return false
                var date = $.format.date(new Date(Number(timestamp/1000)), format)
                return date
            } catch (exception) {
                 console.log(exception)
            }
            return false
        }

    $('#date_timepicker_start').datetimepicker({
         mask:true,
         format:'Y/m/d H:i',
         allowBlank: true,
         validateOnBlur: false,
         closeOnDateSelect: true,
         onShow:function( ct ){
           this.setOptions({
                maxDate:getDatetime($('#date_timepicker_end').val(), "yyyy/MM/dd")
            })
         }
    })

    $('#date_timepicker_end').datetimepicker({
          mask:true,
          format:'Y/m/d H:i',
          allowBlank: true,
          validateOnBlur: false,
          closeOnDateSelect: true,
          onShow:function( ct ){
            this.setOptions({
                minDate:getDatetime($('#date_timepicker_start').val(), "yyyy/MM/dd")
           })
          }
    })

    var defaultClick = function(event) {
        if(event.keyCode == 13 ) $("#search").click()
    }

    $( "#search" ).click(function() {
            $('#events').empty()
            $('#spinner').show()
            readFeed()
    })
    $("#service").keyup(defaultClick)
    $("#date_timepicker_start").keyup(defaultClick)
    $("#date_timepicker_end").keyup(defaultClick)
    $("#version").keyup(defaultClick)
    window.addEventListener("load", init, false)
    $(window).unload(function() {
      if(source != null) source.close()
    });


})